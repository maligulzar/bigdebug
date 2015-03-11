package newt.server;

import java.lang.*;
import java.util.*;
import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;

import java.sql.ResultSet;

import org.w3c.dom.*;

import newt.actor.*;
import newt.server.sql.*;

public class NewtTrace extends Thread {
    static final int ACTORID_INDEX = 0;
    static final int PARENTID_INDEX = 1;
    static final int ACTORTYPE_INDEX = 2;
    static final int ACTORTABLE_INDEX = 3;
    static final int ACTORURL_INDEX = 4;
    static final int RELATIVEID_INDEX = 5;
    static final int SOURCEACTOR_INDEX = 6;
    static final int DESTINATIONACTOR_INDEX = 7;
    static final int SOURCEACTORTYPE_INDEX = 8;
    static final int DESTINATIONACTORTYPE_INDEX = 9;
    static final int INPUTCOLUMN_INDEX = 10;
    static final int INPUTDATATYPE_INDEX = 11;
    static final int OUTPUTCOLUMN_INDEX = 12;
    static final int OUTPUTDATATYPE_INDEX = 13;

    static NewtState    newtState = null;
    private int         traceID = -1;
    private String      direction = null;
    private Vector      data = null;
    private Vector      locatableData = null;
    private boolean     constructQuery = true;
    private String      selfUrl = null;
    private int         startID = -1;
    private String      startType = null;
    private String      tableName = null;
    private NewtSelectStatementBuilder statement = null;

    // HashMap<SourceTable, HashMap<HostNode, Vector<Vector<TableName, SelectColumns, WhereColumns, IsLocatableJoin>>>>
    HashMap<String, HashMap<String, Vector<Vector>>> TraceQuery = null; 
    HashMap<String, Vector<String>> TableSources = null;
    HashMap<Integer, Vector> allActors = null;
    HashMap<String, Vector<Integer>> typeActors = null;
    Vector<Integer> locatableFlowActors = null;
    Vector<String> completedTraces = null;
    Vector<String> completedTables = null;
    Vector<String> selfTables = null;
    Vector<String> updateTraceTable = null;

    HashMap<String, String[]> traceResults = null;
    
    public NewtTrace( int traceID, Vector data, String direction, NewtState newtState, int startID, String startType )
    {
        this.traceID = traceID;
        this.data = data;
        this.direction = direction;
        this.newtState = newtState;
        this.startID = startID;
        this.startType = startType;
        
        TraceQuery = new HashMap<String, HashMap<String, Vector<Vector>>>();
        TableSources = new HashMap<String, Vector<String>>();
        allActors = new HashMap<Integer, Vector>();
        typeActors = new HashMap<String, Vector<Integer>>();
        locatableFlowActors = new Vector<Integer>();
        updateTraceTable = new Vector<String>();
        completedTables = new Vector<String>();
    }

    public NewtTrace( int traceID, 
                      Vector data, 
                      Vector locatableData, 
                      HashMap<String, HashMap<String, Vector<Vector>>> TraceQuery, 
                      HashMap<String, Vector<String>> TableSources, 
                      NewtState newtState,
                      String selfUrl ) 
    {
        this.traceID = traceID;
        this.data = data;
        this.locatableData = locatableData;
        this.TraceQuery = TraceQuery;
        this.TableSources = TableSources;
        this.newtState = newtState;
        this.selfUrl = selfUrl;
        this.constructQuery = false;

        completedTraces = new Vector<String>();
        completedTables = new Vector<String>();
        selfTables = new Vector<String>();
        traceResults = newtState.getTraceResultsMap( traceID );
    }

    public int getID() 
    {
        return traceID;
    }

    public synchronized void setTraceTable( String tableName )
    {
        this.tableName = tableName;
    }

    public synchronized ResultSet getActorDetailsForQuery( Integer[] aids, boolean isParent )
    {
        String[] tables = new String[] { "Newt.actorInstances", "Newt.actorGset" };
        String[] columns =  new String[] { "ActorID", "ParentID", "actorInstances.ActorType", "ActorTable", "ActorUrl", "RelativeID", "SourceActor",
                                           "DestinationActor", "SourceActorType", "DestinationActorType", "Committed", "SchemaTable" };
        statement.addTables( tables );
        statement.addColumns( columns );
        statement.addAbsoluteConditions( (isParent ? "ParentID" : "ActorID"), (Object[]) aids );
        statement.addJoinCondition( "actorInstances", "ActorType", "actorGset", "ActorType" );
        statement.setQuery( true );
        return statement.execute();
    }

    public synchronized boolean processActorDetailsForQuery( Vector actorDetails, ResultSet rs )
    throws Exception
    {
        String committed = rs.getString( 11 ); // Committed
        String actorType = rs.getString( ACTORTYPE_INDEX + 1 );
        if( !committed.equals( "True" ) && !actorType.equals( "RootActor" ) ) {
            return false;
        }

        actorDetails.add( ACTORID_INDEX, rs.getInt( ACTORID_INDEX + 1 ) );
        actorDetails.add( PARENTID_INDEX, rs.getInt( PARENTID_INDEX + 1 ) );
        actorDetails.add( ACTORTYPE_INDEX, rs.getString( ACTORTYPE_INDEX + 1 ) );

        if( !actorType.equals( "RootActor" ) ) {
            actorDetails.add( ACTORTABLE_INDEX, rs.getString( ACTORTABLE_INDEX + 1 ) );
            actorDetails.add( ACTORURL_INDEX, rs.getString( ACTORURL_INDEX + 1 )  );
            actorDetails.add( RELATIVEID_INDEX, rs.getString( RELATIVEID_INDEX + 1 ) );
            actorDetails.add( SOURCEACTOR_INDEX, rs.getString( SOURCEACTOR_INDEX + 1 ) ); // <---------------------------- Vicky changs for PowerGraph
            actorDetails.add( DESTINATIONACTOR_INDEX, rs.getInt( DESTINATIONACTOR_INDEX + 1 ) );
            actorDetails.add( SOURCEACTORTYPE_INDEX, rs.getString( SOURCEACTORTYPE_INDEX + 1 ) );
            actorDetails.add( DESTINATIONACTORTYPE_INDEX, rs.getString( DESTINATIONACTORTYPE_INDEX + 1 ) );
        
            String tname = rs.getString( 12 ); // SchemaTable
            NewtSelectStatementBuilder statement = new NewtSelectStatementBuilder();
            statement.addTable( "Newt", tname );
            statement.addColumn( "ColumnName" );
            statement.addColumn( "ColumnType" );
            statement.addColumn( "SqlDataType" );
            statement.addAbsoluteConditions( "ColumnType", new Object[] { "input", "output" } );
            statement.setQuery( false );
            ResultSet result = statement.execute();

            String inputCol = null;
            String inputDataType = null;
            String outputCol = null;
            String outputDataType = null;
            while( result.next() ) {
                String ctype = result.getString( 2 );
                if( ctype.equals( "input" ) ) {
                    inputCol = result.getString( 1 );
                    inputDataType = result.getString( 3 );
                } else {
                    outputCol = result.getString( 1 );
                    outputDataType = result.getString( 3 );
                }
            }
            actorDetails.add( INPUTCOLUMN_INDEX, inputCol );
            actorDetails.add( INPUTDATATYPE_INDEX, inputDataType );
            actorDetails.add( OUTPUTCOLUMN_INDEX, outputCol );
            actorDetails.add( OUTPUTDATATYPE_INDEX, outputDataType );

            statement.close();
        }
        return true;
    }

    public synchronized int trace( int caid, int sid, String stype )
    {
        if( sid == -1 && stype == null ) {
            System.err.println( "Error: Both start actor ID and start actor type unspecified. Aborting tracing query...");
            return -1;
        }

        Vector<Integer> aids = new Vector<Integer>();
        aids.add( caid );
        statement = new NewtSelectStatementBuilder();
        Vector cActorDetails = new Vector();
        try {
            ResultSet rs = getActorDetailsForQuery( new Integer[] { caid }, false );
            if( !rs.next() ) {
                System.err.println( "Error: Containing actor not found, cannot proceed..." );
                return -1;
            }

            boolean committed = processActorDetailsForQuery( cActorDetails, rs );
            rs.close();
            if( !committed ) {
               System.out.println( "Error: Containing actor hasn't committed yet, aborting trace..." );
                return -1;
            }
        } catch( Exception e ) {
            System.err.println( "Error: Couldn't find all details for trace query, trace failed: " + e.getMessage() );
            e.printStackTrace();
            return -1;
        }

        String catype = (String) cActorDetails.get( ACTORTYPE_INDEX );
        if( !catype.equals( "RootActor" ) ) {
            allActors.put( caid, cActorDetails);
            typeActors.put( catype, new Vector<Integer>() );
            typeActors.get( catype ).add( caid );
        }
        
        if( !( caid == sid || catype.equals( stype ) ) ) {
            do {
                Integer[] values = new Integer[ aids.size() ];
                values = aids.toArray( values );
                ResultSet rs = getActorDetailsForQuery( values, true );

                try {
                    if( !rs.next() ) {
                        break;
                    } else {
                        aids.clear();
                        do {
                            int aid = rs.getInt( 1 );
                            aids.add( aid );

                            Vector actorDetails = new Vector();
                            boolean committed = processActorDetailsForQuery( actorDetails, rs );
                            if( !committed ) {
                               System.out.println( "Error: Not all actors in the trace path have committed. Aborting trace..." );
                                return -1;
                            }

                            allActors.put( aid, actorDetails);
                            if( typeActors.containsKey( actorDetails.get( ACTORTYPE_INDEX ) ) ) {
                                typeActors.get( actorDetails.get( ACTORTYPE_INDEX ) ).add( aid );
                            } else {
                                Vector<Integer> typeAids = new Vector<Integer>();
                                typeAids.add( aid );
                                typeActors.put( (String)actorDetails.get( ACTORTYPE_INDEX ), typeAids );
                            }
                        } while( rs.next() );
                    }

                    rs.close();
                } catch( Exception e ) {
                    e.printStackTrace();
                    break;
                }
            } while( true );
        }

        if( sid != -1 && !allActors.containsKey( sid ) ) {
            System.err.println( "Error: Start actor: " + sid + " isn't contained in containing actor: " + caid );
            return -1;
        } else if( stype != null && !typeActors.containsKey( stype ) ) {
            System.err.println( "Error: Start actor type: " + stype + " isn't contained in containing actor type: " + catype );
            return -1;
        }

        statement.close();
        return traceID;
    }

    public void run()
    {
        try{
        if( constructQuery ) {
            // Create trace metadata table
            NewtCreateStatementBuilder createStatement = new NewtCreateStatementBuilder();
            createStatement.setTable( "Trace", tableName );
            createStatement.addColumn( "ActorType", "varchar(255)", true );
            createStatement.addColumn( "RelativeID", "varchar(255)", true );
            createStatement.addColumn( "ActorID", "int", true );
            createStatement.addColumn( "ParentID", "int", true );
            createStatement.addColumn( "ActorTable", "varchar(255)", true );
            createStatement.addColumn( "ActorUrl", "varchar(255)", true );
            updateTraceTable.add( createStatement.getQuery() );
            createStatement.close();
            
            // Begin constructing query
            Vector<Integer> transitiveActorIDs = new Vector<Integer>();
            if( startID != -1 ) {
                transitiveActorIDs.add( startID );
            } else {
                transitiveActorIDs.addAll( typeActors.get( startType ) );
            }

            constructQuery( transitiveActorIDs, "$data", false );

            while( locatableFlowActors.size() > 0 ) {
                transitiveActorIDs.clear();
                Vector<Integer> laids = new Vector<Integer>( locatableFlowActors );
                locatableFlowActors.clear();
                for ( int aid: laids ) {
                    // Get all data parents for those with locatable outputs (backward trace) or inputs (forward trace).
                    String targetLocatableType = direction.equals( "forward" ) ? "Read" : "Write";
                    Vector<String> locatables = new Vector<String>();
                    statement = new NewtSelectStatementBuilder();
                    statement.addTable( "Newt", "dataInstances" );
                    statement.addColumn( "Locatable" );
                    statement.addAbsoluteCondition( "ActorID", aid );
                    statement.addAbsoluteCondition( "ReadOrWrite", targetLocatableType );
                    statement.setQuery( true );
                    ResultSet rs = statement.execute();

                    try {
                        while( rs.next() ) {
                            String locatable = FileLocatable.getParentDirectory( rs.getString( 1 ) );
                            while( locatable != null && !locatable.trim().equals( "" ) ) {
                                locatables.add( locatable );
                                locatable = FileLocatable.getParentDirectory( locatable );
                            }
                        }
                    } catch( Exception e ) {
                        System.err.println( "Error while constructing trace query,"
                                            + "proceeding with incomplete execution. All results may not be available." );
                        e.printStackTrace();
                    }

                    statement.addTable( "Newt", "dataInstances" );
                    statement.addColumn( "ActorID" );
                    statement.addAbsoluteConditions( "Locatable", locatables.toArray() );
                    statement.addAbsoluteCondition( "ReadOrWrite", targetLocatableType );
                    statement.setQuery( true );
                    rs = statement.execute();

                    try {
                        System.err.print( "Join output of: " );
                        while( rs.next() ) {
                            int dpid = rs.getInt( 1 );
                            if( allActors.containsKey( dpid ) ) { 
                                transitiveActorIDs.add( dpid );        
                                System.err.print( dpid + ", " );
                            }
                        }
                    } catch( Exception e ) {
                    System.err.println( "Error while constructing trace query,"
                                            + "proceeding with incomplete execution. All results may not be available." );
                        e.printStackTrace();
                    }

                    statement.close();

                    locatables.clear();
                    if( aid == startID || allActors.get( aid ).get( ACTORTYPE_INDEX ).equals( startType ) ) {
                        locatableData = new Vector<String>();
                       for( Object o: data ) {
                            String locatable = FileLocatable.getParentDirectory( FileLocatable.getPathname( (String) o ) );
                            while( locatable != null && !locatable.trim().equals( "" ) && !locatableData.contains( locatable ) ) {
                                locatableData.add( locatable );
                                locatable = FileLocatable.getParentDirectory( locatable );
                            }
                        }

                        System.err.println( "with: " );
                        for( Object l: locatableData ) {
                            System.err.println( (String)l );
                        }
                        
                        constructQuery( transitiveActorIDs, "$locatableData", false );
                    } else {
                        constructQuery( transitiveActorIDs, (String) allActors.get( aid ).get( ACTORTABLE_INDEX ), true );
                    }
                }
            }

            Set entrySet = TraceQuery.entrySet();
           System.out.println( "Query construction complete: " );
            for( Object eo: entrySet ) {
                Map.Entry e = (Map.Entry) eo;
                String st = (String) e.getKey();
                HashMap targetTables = (HashMap<String, Vector<Vector>>) e.getValue();
                Set targetEntries = targetTables.entrySet();

                for( Object to: targetEntries ) {
                    Map.Entry t = (Map.Entry) to;
                    String url = (String) t.getKey();
                    Vector<Vector> tables = (Vector<Vector>) t.getValue();

                    for( Object tableo: tables ) {
                        Vector table = (Vector) tableo;
                        String tableName = (String) table.get( 0 );
                        Boolean locatableJoin =  Boolean.valueOf((String)table.get( 4 ));
                       System.out.print( (locatableJoin ? "Locatable join " : "Join ") );
                       System.out.println( "result of " + st + " with " + tableName + " on node " + url );
                    }
                }
            }

            newtState.updateBatch( updateTraceTable );

            newtState.sendTrace( traceID, TraceQuery, TableSources, data, locatableData );
        } else {
            Set entrySet = TraceQuery.entrySet();
            for( Object eo: entrySet ) {
                Map.Entry e = (Map.Entry) eo;
                HashMap targetTables = (HashMap) e.getValue();
                Set targetEntries = targetTables.entrySet();

                for( Object to: targetEntries ) {
                    Map.Entry t = (Map.Entry) to;
                    String url = (String) t.getKey();

                    if( url.equals( selfUrl ) ) {
                        Vector tables = (Vector) t.getValue();
                        for( Object tableo: tables ) {
                            Vector table = (Vector) tableo;
                           System.out.println( "Selftable: " + (String) table.get( 0 ) );
                            selfTables.add( (String) table.get( 0 ) );
                        }
                    }
                }
            }
            traceLocal(); 
        }
        }catch( Exception e)
        { e.printStackTrace();
        }
    }

    public void constructQuery( Vector<Integer> actorIDs, String sourceTable, boolean locatableJoin )
    {
        if( completedTables.contains( sourceTable ) ) {
            return;
        }

        Vector<Integer> transitiveActorIDs = new Vector<Integer>();
        
        for( int aid : actorIDs ) {
            transitiveActorIDs.clear();
            Vector actorDetails = new Vector( allActors.get( aid ) );

            updateTraceTable.add( "insert into Trace." + tableName + " values ( '" 
                                  + actorDetails.get( ACTORTYPE_INDEX ) + "', '"
                                  + actorDetails.get( RELATIVEID_INDEX ) + "', "
                                  + actorDetails.get( ACTORID_INDEX ) + ", "
                                  + actorDetails.get( PARENTID_INDEX ) + ", '"
                                  + actorDetails.get( ACTORTABLE_INDEX ) + "', '"
                                  + actorDetails.get( ACTORURL_INDEX ) + "' )" );

            Vector schemaDetails = new Vector();
            String selectColumn = null;
            String selectColumnType = null;
            String inputColumn = null;
            String inputColumnType = null;
            Vector<String> whereColumns = new Vector<String>();
            
            schemaDetails.add( actorDetails.get( ACTORTABLE_INDEX ));
            // Select output (forward), input (backward)
            selectColumn = (String)actorDetails.get( (direction.equals( "forward" ) ? OUTPUTCOLUMN_INDEX : INPUTCOLUMN_INDEX ) );
            selectColumnType = (String)actorDetails.get( (direction.equals( "forward" ) ? OUTPUTDATATYPE_INDEX : INPUTDATATYPE_INDEX ) );
            inputColumn = (String)actorDetails.get( INPUTCOLUMN_INDEX );
            inputColumnType = (String)actorDetails.get( INPUTDATATYPE_INDEX );
            // Join on input (forward), output (backward)
            whereColumns.add( (String)actorDetails.get( (direction.equals( "forward" ) ? INPUTCOLUMN_INDEX : OUTPUTCOLUMN_INDEX ) ) ); 
            schemaDetails.add( selectColumn );
            schemaDetails.add( selectColumnType );
            schemaDetails.add( inputColumn );
            schemaDetails.add( inputColumnType );
            schemaDetails.add( whereColumns );
            schemaDetails.add( locatableJoin );

            Vector<Vector> schemaList = new Vector<Vector>();
            schemaList.add( schemaDetails );

            if( TraceQuery.containsKey( sourceTable ) ) {
                HashMap<String, Vector<Vector>> nodeTables = (HashMap<String, Vector<Vector>>)TraceQuery.get( sourceTable );
                if( nodeTables.containsKey( (String)actorDetails.get( ACTORURL_INDEX ) ) ) {
                    Vector<Vector> nodesTables = (Vector<Vector>)nodeTables.get( (String)actorDetails.get( ACTORURL_INDEX ) );
                    nodesTables.add( schemaDetails );
                } else {
                    nodeTables.put( (String)actorDetails.get( ACTORURL_INDEX ), schemaList );
                }
            } else {
                HashMap<String, Vector<Vector>> nodeTables = new HashMap<String, Vector<Vector>>();
                nodeTables.put( (String)actorDetails.get( ACTORURL_INDEX ), schemaList );
                TraceQuery.put( sourceTable, nodeTables );
            }

            if( TableSources.containsKey( actorDetails.get( ACTORTABLE_INDEX ) ) ) {
                 TableSources.get( actorDetails.get( ACTORTABLE_INDEX ) ).add( sourceTable );
            } else {
                Vector<String> sourceTables = new Vector<String>();
                sourceTables.add( sourceTable );
                TableSources.put( (String)actorDetails.get( ACTORTABLE_INDEX ), sourceTables );
            }

            if( direction.equals( "forward" ) ) {
                if( (Integer)actorDetails.get( DESTINATIONACTOR_INDEX ) != -1 ) {
                    Integer i = (Integer)actorDetails.get( DESTINATIONACTOR_INDEX );
                    if( (Vector)allActors.get( i ) != null ) {
                        transitiveActorIDs.add( i );
                    }
                } else if( !((String)actorDetails.get( DESTINATIONACTORTYPE_INDEX )).toLowerCase().equals( "filelocatable" ) ) {
                    Vector<Integer> transitiveTypeActors = (Vector<Integer>)typeActors.get( (String)actorDetails.get( DESTINATIONACTORTYPE_INDEX ) );
                   //TODO Ksh possible bug?
                    if(transitiveTypeActors != null) {
                        for (Object o : transitiveTypeActors) {
                            Integer i = (Integer) o;
                            if ((Integer) ((Vector) allActors.get(i)).get(PARENTID_INDEX) == (Integer) actorDetails.get(PARENTID_INDEX)) {
                                transitiveActorIDs.add(i);
                            }
                        }
                    }
                }
            } else if( direction.equals( "backward" ) ) { // <---------------------------------------- Vicky changes for PowerGraph
                if( (String)actorDetails.get( SOURCEACTOR_INDEX ) != "" ) {
                	String src = (String)actorDetails.get( SOURCEACTOR_INDEX );
//                    Integer i = (Integer)actorDetails.get( SOURCEACTOR_INDEX );
                	for(String s: src.split(" "))
                	{
	                    if( (Vector)allActors.get( Integer.valueOf(s) ) != null ) {
	                        transitiveActorIDs.add( Integer.valueOf(s) );
	                    }
                	}
                } 
                else if( !((String)actorDetails.get( SOURCEACTORTYPE_INDEX )).toLowerCase().equals( "filelocatable" ) ) {
                    Vector<Integer> transitiveTypeActors = (Vector<Integer>)typeActors.get( (String)actorDetails.get( SOURCEACTORTYPE_INDEX ) );
                    if(transitiveTypeActors != null)
                    {
	                    for( Object o: transitiveTypeActors ) {
	                        Integer i = (Integer) o;
	                        if( (Integer)((Vector)allActors.get( i )).get( PARENTID_INDEX ) == (Integer)actorDetails.get( PARENTID_INDEX ) ) {
	                            transitiveActorIDs.add( i );
	                        }
	                    }
                    }
                }

                if( ((String)actorDetails.get( DESTINATIONACTORTYPE_INDEX )).toLowerCase().equals( "filelocatable" ) 
                     && !((String)actorDetails.get( ACTORTYPE_INDEX )).equals( "GHOST" ) ) {
                    locatableFlowActors.add( aid );
                }
            }

            constructQuery(transitiveActorIDs, (String) actorDetails.get(ACTORTABLE_INDEX), false);
        }

        completedTables.add( sourceTable );
    }

    public synchronized void createTraceTable( String tableName, Vector tableData )
    {
        NewtCreateStatementBuilder createStatement = new NewtCreateStatementBuilder();
        createStatement.setTable( "Trace", tableName );
        createStatement.addColumn( "Result", "varchar(1024)", true );
        createStatement.setQuery();
        createStatement.execute();
        createStatement.close();

        NewtBulkInsertStatementBuilder bulkInsertStatement = new NewtBulkInsertStatementBuilder();
        bulkInsertStatement.setTable( "Trace", tableName );
        bulkInsertStatement.setRowLength( 1 );
        for( Object o: tableData ) {
            bulkInsertStatement.addRow( new Object[] { o } );
        }
        bulkInsertStatement.setQuery();
        bulkInsertStatement.execute();
        bulkInsertStatement.close();
    }

    public synchronized void traceLocal()
    {
        if( !TraceQuery.containsKey( "$data" ) ) {
            System.err.println( "Error: Cannot begin trace..." );
            return;
        }

        createTraceTable( traceID + "_data", data );
        if( locatableData != null ) {
            createTraceTable( traceID + "_locatabledata", locatableData );
        }

        Vector<String> sourceTables = new Vector<String>();
        sourceTables.add( "$data" ); //  Beginning with data
        synchronized( traceResults ) {
            traceResults.put( "$data", new String[] { "Trace." + traceID + "_data", null } );
        }

        Vector<String> createQuery = new Vector<String>();
        HashMap<String[], Vector<String>> selectQuery = new HashMap<String[], Vector<String>>();

        Vector queryTables = null;
        HashMap<String, Vector<Vector>> nodeTables = (HashMap<String, Vector<Vector>>)TraceQuery.get( "$data" );
        if( nodeTables != null && (nodeTables.containsKey( selfUrl ) ) ) {
            Object nodeTable = nodeTables.get( selfUrl );
            queryTables = (Vector) nodeTable;

            writeQuery( sourceTables, queryTables, createQuery, selectQuery );
        } else {
            completedTraces.add( "$data" );
        }

        if( locatableData != null ) {
            sourceTables.clear();
            sourceTables.add( "$locatableData" ); // Continue with data parents
            synchronized( traceResults ) {
                traceResults.put( "$locatableData", new String[] { "Trace." + traceID + "_locatabledata", null } );
            }

            nodeTables = (HashMap<String, Vector<Vector>>)TraceQuery.get( "$locatableData" );
            if( nodeTables != null && (nodeTables.containsKey( selfUrl ) ) ) {
                Object nodeTable = nodeTables.get( selfUrl );
                queryTables = (Vector) nodeTable;

                writeQuery( sourceTables, queryTables, createQuery, selectQuery );
            } else {
                completedTraces.add( "$locatableData" );
            }
        }

        newtState.updateBatch( createQuery );

        forwardResults( selectQuery );

        while( !completedTables.containsAll( selfTables ) ) {
            continueTrace();
        }

       System.out.println( "Local trace completed... Notifying master" );
        newtState.notifyTraceComplete( traceID );
    }

    protected void forwardResults( HashMap<String[], Vector<String>> selectQuery )
    {
        statement = new NewtSelectStatementBuilder(); 
        if(selectQuery == null) return;
        
        Set selectQueries = selectQuery.entrySet();
        
        for( Object o: selectQueries ) {
            Map.Entry m = (Map.Entry) o;
            statement.addTable( "Trace", ((String[]) m.getKey())[ 0 ] );
            statement.addColumn( "*" );
            statement.setQuery( false );
            ResultSet rs = statement.execute();

            Vector results = new Vector();
            try {
                if( (((String[]) m.getKey())[ 1 ]).startsWith( "varchar" ) ) {
                    while( rs.next() ) {
                        results.add( rs.getString( 1 ) );
                    }
                } else if( (((String[]) m.getKey())[ 1 ]).startsWith( "varbinary" ) ) {
                    while( rs.next() ) {
                        results.add( rs.getBytes( 1 ) );
                    }
                }
            } catch( Exception e ) {
                e.printStackTrace();
            }
           
            Vector<String> targets = (Vector<String>) m.getValue();
            newtState.forwardTraceResults( targets, traceID, (String[]) m.getKey(), results );
        }

        statement.close();
    }

    public synchronized void writeQuery( Vector<String> sourceTables, 
                                         Vector queryTables, 
                                         Vector<String> createQuery, 
                                         HashMap<String[], Vector<String>> selectQuery )
    {
        Vector<String> traceResultsKeys = null;
        synchronized( traceResults ) {
            traceResultsKeys = new Vector<String>( traceResults.keySet() );
        }

        if( queryTables != null ) {
            for( Object o : queryTables ) {
                Vector v = (Vector) o;
                String queryTable = (String) v.get( 0 );
                String selectColumn = (String) v.get( 1 );
                String selectColumnType = (String) v.get( 2 );
                String inputColumn = (String) v.get( 3 );
                String inputColumnType = (String) v.get( 4 );
                Object w = v.get( 5 );
                Vector whereColumns = (Vector) w;
                Boolean locatableJoin = (Boolean) v.get( 6 );

                if( locatableJoin ) {
                    continue;
                }
    
                Object t = TableSources.get( queryTable );
                Vector queryTableSources = (Vector) t;
                
                if( traceResultsKeys.containsAll( queryTableSources ) ) {
                    String create = "create table Trace." + traceID + "$" + queryTable + "$" + selectColumn + " as"
                                    + " select distinct Newt." + queryTable + "." + selectColumn + " as Result,"
                                    + " Newt." + queryTable + "." + inputColumn + " as Input";
                    String fromClause = " from Newt." + queryTable;
                    String whereClause = " where";
                    for( Object e: queryTableSources ) {
                        String table = null;
                        synchronized( traceResults ) {
                            table = traceResults.get( (String) e )[ 0 ];
                        }
                        fromClause += ", " + table;
                        whereClause += " Newt." + queryTable + "." + (String) whereColumns.get( 0 ) + " = " + table + ".Result or ";
                        for( int i = 1; i < whereColumns.size(); i++ ) {
                            whereClause += " or Newt." + queryTable + "." + (String) whereColumns.get( i ) + " = " + table + ".Result";
                        }
                    }
    
                    whereClause = whereClause.substring( 0, whereClause.length() - 4 ); // Remove trailing or
                    create += fromClause + whereClause;
                    createQuery.add( create );
    
                    completedTables.add( queryTable );
                   System.out.println( "QueryTable completed: " + queryTable );
    
                    Vector<String>  transitiveSourceTables = new Vector<String>();
                    transitiveSourceTables.add( queryTable );
                    synchronized( traceResults ) {
                        traceResults.put( queryTable, new String[] { "Trace." + traceID + "$" + queryTable + "$" + selectColumn, inputColumnType } );
                    }
    
                    Vector transitiveQueryTables = null;
                    HashMap<String, Vector<Vector>> nodeTables = TraceQuery.get( queryTable );
                    if( nodeTables == null || nodeTables.size() == 0 ) {
                        completedTraces.add( queryTable );
                       System.out.println( "Trace completed: " + queryTable );
                    } else {
                        Vector targets = new Vector( nodeTables.keySet() );
                        if( (nodeTables.containsKey( selfUrl ) ) ) {
                            targets.remove( selfUrl );
                            Object nodeTable = nodeTables.get( selfUrl );
                            transitiveQueryTables = (Vector)nodeTable;
                            
                            writeQuery( transitiveSourceTables, transitiveQueryTables, createQuery, selectQuery );
                        }

                        if( targets.size() > 0 ) {
                            selectQuery.put( new String[] { traceID + "$" + queryTable + "$" + selectColumn, selectColumnType }, targets );
                        }
                    }
                }
            }
        }

        for( String s : sourceTables ) {
            completedTraces.add( s );
           System.out.println( "Trace completed: " + s );
        }
    }

    public synchronized void continueTrace()
    {
        Vector<String> createQuery = new Vector<String>();
        HashMap<String[], Vector<String>> selectQuery = new HashMap<String[], Vector<String>>();
        Vector<String> traceResultsKeys = null;

        synchronized( traceResults ) {
            traceResultsKeys = new Vector( traceResults.keySet() );
        }

        for( String source: traceResultsKeys ) {
            if( !completedTraces.contains( source ) ) {
               System.out.println( "Source: " + source );
                Vector<String> sourceTables = new Vector<String>();
                sourceTables.add( source );

                Vector queryTables = null;
                HashMap<String, Vector<Vector>> nodeTables = (HashMap<String, Vector<Vector>>)TraceQuery.get( source );
                if( nodeTables == null || !nodeTables.containsKey( selfUrl ) ) {
                    completedTraces.add( source );
                } else {
                    Object nodeTable = nodeTables.get( selfUrl );
                    queryTables = (Vector)nodeTable;
                    writeQuery( sourceTables, queryTables, createQuery, selectQuery );
                }
            }
        }

        newtState.updateBatch( createQuery );

        forwardResults( selectQuery );
    }
}