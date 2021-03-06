/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.runtime;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import org.neo4j.bolt.AbstractBoltTransportsTest;
import org.neo4j.bolt.testing.client.TransportConnection;
import org.neo4j.bolt.transport.Neo4jWithSocket;
import org.neo4j.bolt.v4.messaging.BoltV4Messages;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.rule.fs.EphemeralFileSystemRule;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.neo4j.bolt.testing.MessageConditions.msgFailure;
import static org.neo4j.bolt.testing.MessageConditions.msgSuccess;
import static org.neo4j.logging.AssertableLogProvider.Level.ERROR;
import static org.neo4j.logging.LogAssertions.assertThat;

@RunWith( Parameterized.class )
public class BoltSchedulerBusyIT extends AbstractBoltTransportsTest
{
    private AssertableLogProvider internalLogProvider = new AssertableLogProvider();
    private AssertableLogProvider userLogProvider = new AssertableLogProvider();
    private EphemeralFileSystemRule fsRule = new EphemeralFileSystemRule();
    private Neo4jWithSocket server = new Neo4jWithSocket( getClass(), getTestGraphDatabaseFactory(), fsRule, getSettingsFunction() );
    private TransportConnection connection1;
    private TransportConnection connection2;
    private TransportConnection connection3;
    private TransportConnection connection4;

    @Rule
    public RuleChain ruleChain = RuleChain.outerRule( fsRule ).around( server );

    private TestDatabaseManagementServiceBuilder getTestGraphDatabaseFactory()
    {
        TestDatabaseManagementServiceBuilder factory = new TestDatabaseManagementServiceBuilder();
        factory.setInternalLogProvider( internalLogProvider );
        factory.setUserLogProvider( userLogProvider );
        return factory;
    }

    protected Consumer<Map<Setting<?>,Object>> getSettingsFunction()
    {
        return settings ->
        {
            super.getSettingsFunction().accept( settings );
            settings.put( BoltConnector.listen_address, new SocketAddress( "localhost", 0 ) );
            settings.put( BoltConnector.thread_pool_min_size, 0 );
            settings.put( BoltConnector.thread_pool_max_size, 2 );
        };
    }

    @Before
    public void setup() throws Exception
    {
        address = server.lookupDefaultConnector();
    }

    @After
    public void cleanup() throws Exception
    {
        close( connection1 );
        close( connection2 );
        close( connection3 );
        close( connection4 );
    }

    @Test
    public void shouldReportFailureWhenAllThreadsInThreadPoolAreBusy() throws Throwable
    {
        // it's enough to get the bolt state machine into streaming mode to have
        // the thread sticked to the connection, causing all the available threads
        // to be busy (logically)
        connection1 = enterStreaming();
        connection2 = enterStreaming();

        try
        {
            connection3 = connectAndPerformBoltHandshake( newConnection() );

            connection3.send( util.defaultAuth() );
            assertThat( connection3 ).satisfies( util.eventuallyReceives(
                    msgFailure( Status.Request.NoThreadsAvailable, "There are no available threads to serve this request at the moment" ) ) );

            assertThat( userLogProvider ).containsMessages(
                    "since there are no available threads to serve it at the moment. You can retry at a later time" );
            assertThat( internalLogProvider ).forClass( DefaultBoltConnection.class ).forLevel( ERROR )
                    .assertExceptionForLogMessage( "since there are no available threads to serve it at the moment. You can retry at a later time" )
                    .isInstanceOf( RejectedExecutionException.class );
        }
        finally
        {
            exitStreaming( connection1 );
            exitStreaming( connection2 );
        }
    }

    @Test
    public void shouldStopConnectionsWhenRelatedJobIsRejectedOnShutdown() throws Throwable
    {
        // Connect and get two connections into idle state
        connection1 = enterStreaming();
        exitStreaming( connection1 );
        connection2 = enterStreaming();
        exitStreaming( connection2 );

        // Connect and get other set of connections to keep threads busy
        connection3 = enterStreaming();
        connection4 = enterStreaming();

        // Clear any log output till now
        internalLogProvider.clear();

        // Shutdown the server
        server.shutdownDatabase();

        // Expect no scheduling error logs
        assertThat( userLogProvider ).doesNotContainMessage(
                "since there are no available threads to serve it at the moment. You can retry at a later time" );
        assertThat( internalLogProvider ).doesNotContainMessage(
                "since there are no available threads to serve it at the moment. You can retry at a later time" );
    }

    private TransportConnection enterStreaming() throws Throwable
    {
        TransportConnection connection = null;
        Throwable error = null;

        // retry couple times because worker threads might seem busy
        for ( int i = 1; i <= 7; i++ )
        {
            try
            {
                connection = newConnection();
                enterStreaming( connection, i );
                error = null;
                return connection;
            }
            catch ( Throwable t )
            {
                // failed to enter the streaming state, record the error and retry
                if ( error == null )
                {
                    error = t;
                }
                else
                {
                    error.addSuppressed( t );
                }

                close( connection );
                SECONDS.sleep( i );
            }
        }

        if ( error != null )
        {
            throw error;
        }

        throw new IllegalStateException( "Unable to enter the streaming state" );
    }

    private void enterStreaming( TransportConnection connection, int sleepSeconds ) throws Exception
    {
        connectAndPerformBoltHandshake( connection );

        connection.send( util.defaultAuth() );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );

        SECONDS.sleep( sleepSeconds ); // sleep a bit to allow worker thread return back to the pool

        connection.send( util.chunk( BoltV4Messages.run( "UNWIND RANGE (1, 100) AS x RETURN x" ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );
    }

    private TransportConnection connectAndPerformBoltHandshake( TransportConnection connection ) throws Exception
    {
        connection.connect( address ).send( util.defaultAcceptedVersions() );
        assertThat( connection ).satisfies( util.eventuallyReceivesSelectedProtocolVersion() );
        return connection;
    }

    private void exitStreaming( TransportConnection connection ) throws Exception
    {
        connection.send( util.chunk( BoltV4Messages.discardAll() ) );

        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );
    }

    private void close( TransportConnection connection )
    {
        if ( connection != null )
        {
            try
            {
                connection.disconnect();
            }
            catch ( IOException ignore )
            {
            }
        }
    }
}
