/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  *  contributor license agreements.  The ASF licenses this file to You
 *  * under the Apache License, Version 2.0 (the "License"); you may not
 *  * use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.  For additional information regarding
 *  * copyright in this work, please see the NOTICE file in the top level
 *  * directory of this distribution.
 *
 */
package org.apache.usergrid.persistence.index.impl;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.deletebyquery.IndexDeleteByQueryResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequestBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.usergrid.persistence.core.metrics.MetricsFactory;
import org.apache.usergrid.persistence.core.scope.ApplicationScope;
import org.apache.usergrid.persistence.core.util.ValidationUtils;
import org.apache.usergrid.persistence.index.AliasedEntityIndex;
import org.apache.usergrid.persistence.index.ApplicationEntityIndex;
import org.apache.usergrid.persistence.index.CandidateResult;
import org.apache.usergrid.persistence.index.CandidateResults;
import org.apache.usergrid.persistence.index.EntityIndexBatch;
import org.apache.usergrid.persistence.index.IndexFig;
import org.apache.usergrid.persistence.index.SearchEdge;
import org.apache.usergrid.persistence.index.SearchTypes;
import org.apache.usergrid.persistence.index.exceptions.QueryException;
import org.apache.usergrid.persistence.index.query.ParsedQuery;
import org.apache.usergrid.persistence.index.query.ParsedQueryBuilder;
import org.apache.usergrid.persistence.index.utils.IndexValidationUtils;
import org.apache.usergrid.persistence.map.MapManager;
import org.apache.usergrid.persistence.map.MapManagerFactory;
import org.apache.usergrid.persistence.map.MapScope;
import org.apache.usergrid.persistence.map.impl.MapScopeImpl;
import org.apache.usergrid.persistence.model.entity.Id;
import org.apache.usergrid.persistence.model.entity.SimpleId;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import rx.Observable;

import static org.apache.usergrid.persistence.index.impl.IndexingUtils.APPLICATION_ID_FIELDNAME;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.parseIndexDocId;


/**
 * Classy class class.
 */
public class EsApplicationEntityIndexImpl implements ApplicationEntityIndex {

    private static final Logger logger = LoggerFactory.getLogger( EsApplicationEntityIndexImpl.class );


    private final ApplicationScope applicationScope;
    private final FailureMonitorImpl.IndexIdentifier indexIdentifier;
    private final Timer searchTimer;
    private final Timer cursorTimer;
    private final MapManager mapManager;
    private final AliasedEntityIndex entityIndex;
    private final IndexBufferProducer indexBatchBufferProducer;
    private final IndexCache indexCache;
    private final IndexFig indexFig;
    private final EsProvider esProvider;
    private final IndexAlias alias;
    private final Timer deleteApplicationTimer;
    private final Meter deleteApplicationMeter;
    private final SearchRequestBuilderStrategy searchRequest;
    private FailureMonitor failureMonitor;
    private final int cursorTimeout;


    @Inject
    public EsApplicationEntityIndexImpl( @Assisted ApplicationScope appScope, final AliasedEntityIndex entityIndex,
                                         final IndexFig config, final IndexBufferProducer indexBatchBufferProducer,
                                         final EsProvider provider, final IndexCache indexCache,
                                         final MetricsFactory metricsFactory, final MapManagerFactory mapManagerFactory,
                                         final IndexFig indexFig,
                                         final FailureMonitorImpl.IndexIdentifier indexIdentifier ) {
        this.entityIndex = entityIndex;
        this.indexBatchBufferProducer = indexBatchBufferProducer;
        this.indexCache = indexCache;
        this.indexFig = indexFig;
        this.indexIdentifier = indexIdentifier;
        ValidationUtils.validateApplicationScope( appScope );
        this.applicationScope = appScope;
        final MapScope mapScope = new MapScopeImpl( appScope.getApplication(), "cursorcache" );
        this.failureMonitor = new FailureMonitorImpl( config, provider );
        this.esProvider = provider;

        mapManager = mapManagerFactory.createMapManager( mapScope );
        this.searchTimer = metricsFactory.getTimer( EsApplicationEntityIndexImpl.class, "search.timer" );
        this.cursorTimer = metricsFactory.getTimer( EsApplicationEntityIndexImpl.class, "search.cursor.timer" );
        this.cursorTimeout = config.getQueryCursorTimeout();

        this.deleteApplicationTimer =
                metricsFactory.getTimer( EsApplicationEntityIndexImpl.class, "delete.application" );
        this.deleteApplicationMeter =
                metricsFactory.getMeter( EsApplicationEntityIndexImpl.class, "delete.application.meter" );

        this.alias = indexIdentifier.getAlias();

        this.searchRequest = new SearchRequestBuilderStrategy( esProvider, appScope, alias, cursorTimeout );
    }


    @Override
    public EntityIndexBatch createBatch() {
        EntityIndexBatch batch =
                new EsEntityIndexBatchImpl( applicationScope, indexBatchBufferProducer, entityIndex, indexIdentifier );
        return batch;
    }


    @Override
    public CandidateResults search( final SearchEdge searchEdge, final SearchTypes searchTypes, final String query,
                                    final int limit ) {

        IndexValidationUtils.validateSearchEdge( searchEdge );
        Preconditions.checkNotNull( searchTypes, "searchTypes cannot be null" );
        Preconditions.checkNotNull( query, "query cannot be null" );
        Preconditions.checkArgument( limit > 0, "limit must be > 0" );


        SearchResponse searchResponse;

        final ParsedQuery parsedQuery = ParsedQueryBuilder.build( query );

        final SearchRequestBuilder srb = searchRequest.getBuilder( searchEdge, searchTypes, parsedQuery, limit );

        if ( logger.isDebugEnabled() ) {
            logger.debug( "Searching index (read alias): {}\n  nodeId: {}, edgeType: {},  \n type: {}\n   query: {} ",
                    this.alias.getReadAlias(), searchEdge.getNodeId(), searchEdge.getEdgeName(),
                    searchTypes.getTypeNames( applicationScope ), srb );
        }

        try {
            //Added For Graphite Metrics
            Timer.Context timeSearch = searchTimer.time();
            searchResponse = srb.execute().actionGet();
            timeSearch.stop();
        }
        catch ( Throwable t ) {
            logger.error( "Unable to communicate with Elasticsearch", t );
            failureMonitor.fail( "Unable to execute batch", t );
            throw t;
        }
        failureMonitor.success();

        return parseResults( searchResponse, parsedQuery, limit );
    }


    public CandidateResults getNextPage( final String cursor ) {
        Preconditions.checkNotNull( cursor, "cursor is a required argument" );

        SearchResponse searchResponse;

        String userCursorString = cursor;

        //sanitiztion, we  probably shouldn't do this here, but in the caller
        if ( userCursorString.startsWith( "\"" ) ) {
            userCursorString = userCursorString.substring( 1 );
        }
        if ( userCursorString.endsWith( "\"" ) ) {
            userCursorString = userCursorString.substring( 0, userCursorString.length() - 1 );
        }

        //now get the cursor from the map  and validate
        final String queryStateString = mapManager.getString( userCursorString );


        if(queryStateString == null){
            throw new QueryException( String.format("Could not find a cursor for the value '%s' ", userCursorString ));
        }



        //parse the query state
        final QueryState queryState = QueryState.fromSerialized( queryStateString );

        //parse the query so we can get select terms
        final ParsedQuery parsedQuery = ParsedQueryBuilder.build( queryState.ql);


        logger.debug( "Executing query with cursor: {} ", queryState.esCursor );


        SearchScrollRequestBuilder ssrb =
                esProvider.getClient().prepareSearchScroll( queryState.esCursor ).setScroll( cursorTimeout + "m" );

        try {
            //Added For Graphite Metrics
            Timer.Context timeSearchCursor = cursorTimer.time();
            searchResponse = ssrb.execute().actionGet();
            timeSearchCursor.stop();
        }
        catch ( Throwable t ) {
            logger.error( "Unable to communicate with elasticsearch", t );
            failureMonitor.fail( "Unable to execute batch", t );
            throw t;
        }


        failureMonitor.success();

        return parseResults( searchResponse, parsedQuery, queryState.limit );
    }


    /**
     * Completely delete an index.
     */
    public Observable deleteApplication() {
        deleteApplicationMeter.mark();
        String idString = IndexingUtils.idString( applicationScope.getApplication() );
        final TermQueryBuilder tqb = QueryBuilders.termQuery( APPLICATION_ID_FIELDNAME, idString );
        final String[] indexes = entityIndex.getUniqueIndexes();
        Timer.Context timer = deleteApplicationTimer.time();
        //Added For Graphite Metrics
        return Observable.from( indexes ).flatMap( index -> {

            final ListenableActionFuture<DeleteByQueryResponse> response =
                    esProvider.getClient().prepareDeleteByQuery( alias.getWriteAlias() ).setQuery( tqb ).execute();

            response.addListener( new ActionListener<DeleteByQueryResponse>() {

                @Override
                public void onResponse( DeleteByQueryResponse response ) {
                    checkDeleteByQueryResponse( tqb, response );
                }


                @Override
                public void onFailure( Throwable e ) {
                    logger.error( "failed on delete index", e );
                }
            } );
            return Observable.from( response );
        } ).doOnError( t -> logger.error( "Failed on delete application", t ) ).doOnCompleted( () -> timer.stop() );
    }


    /**
     * Validate the response doesn't contain errors, if it does, fail fast at the first error we encounter
     */
    private void checkDeleteByQueryResponse( final QueryBuilder query, final DeleteByQueryResponse response ) {

        for ( IndexDeleteByQueryResponse indexDeleteByQueryResponse : response ) {
            final ShardOperationFailedException[] failures = indexDeleteByQueryResponse.getFailures();

            for ( ShardOperationFailedException failedException : failures ) {
                logger.error( String.format( "Unable to delete by query %s. "
                                        + "Failed with code %d and reason %s on shard %s in index %s", query.toString(),
                                failedException.status().getStatus(), failedException.reason(),
                                failedException.shardId(), failedException.index() ) );
            }
        }
    }


    /**
     * Parse the results and return the canddiate results
     */
    private CandidateResults parseResults( final SearchResponse searchResponse, final ParsedQuery query,
                                           final int limit ) {

        final SearchHits searchHits = searchResponse.getHits();
        final SearchHit[] hits = searchHits.getHits();
        final int length = hits.length;

        logger.debug( "   Hit count: {} Total hits: {}", length, searchHits.getTotalHits() );

        List<CandidateResult> candidates = new ArrayList<>( length );

        for ( SearchHit hit : hits ) {

            final CandidateResult candidateResult = parseIndexDocId( hit.getId() );

            candidates.add( candidateResult );
        }

        final CandidateResults candidateResults = new CandidateResults( candidates, query.getSelectFieldMappings() );
        final String esScrollCursor = searchResponse.getScrollId();

        // >= seems odd.  However if our user reduces expectedSize (limit) on subsequent requests, we can't do that
        //therefor we need to account for the overflow
        if ( esScrollCursor != null && length >= limit ) {
            final String cursor = candidateResults.initializeCursor();

            //now set this into our map module
            final int minutes = indexFig.getQueryCursorTimeout();

            final QueryState state = new QueryState( query.getOriginalQuery(), limit, esScrollCursor );

            final String queryStateSerialized = state.serialize();

            //just truncate it, we'll never hit a long value anyway
            mapManager.putString( candidateResults.getCursor(), queryStateSerialized,
                    ( int ) TimeUnit.MINUTES.toSeconds( minutes ) );

            logger.debug( " User cursor = {},  Cursor = {} ", candidateResults.getCursor(), esScrollCursor );
        }

        return candidateResults;
    }


    /**
     * Class to encapsulate our serialized state
     */
    private static final class QueryState {

        /**
         * Our reserved character for constructing our storage string
         */
        private static final String STORAGE_DELIM = "_ugdelim_";

        private final String ql;

        private final int limit;

        private final String esCursor;


        private QueryState( final String ql, final int limit, final String esCursor ) {
            this.ql = ql;
            this.limit = limit;
            this.esCursor = esCursor;
        }


        /**
         * Factory to create an instance of our state from the serialized string
         */
        public static QueryState fromSerialized( final String input ) {

            final String[] parts = input.split( STORAGE_DELIM );

            Preconditions.checkArgument( parts != null && parts.length == 3,
                    "there must be 3 parts to the serialized query state" );


            return new QueryState( parts[0], Integer.parseInt( parts[1] ), parts[2] );
        }


        public String serialize() {

            StringBuilder storageString = new StringBuilder();

            storageString.append( ql ).append( STORAGE_DELIM );

            storageString.append( limit ).append( STORAGE_DELIM );

            storageString.append( esCursor );

            return storageString.toString();
        }
    }
}
