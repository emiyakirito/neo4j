/*
 * Copyright (c) 2002-2018 "Neo4j,"
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
package org.neo4j.jmx.impl;

import javax.management.ObjectName;

import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.kernel.internal.KernelData;

import static org.neo4j.graphdb.factory.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

public final class ManagementData
{
    private final KernelData kernel;
    private final ManagementSupport support;
    private final DatabaseManager databaseManager;
    final ManagementBeanProvider provider;

    public ManagementData( ManagementBeanProvider provider, KernelData kernel, DatabaseManager databaseManager, ManagementSupport support )
    {
        this.provider = provider;
        this.kernel = kernel;
        this.support = support;
        this.databaseManager = databaseManager;
    }

    public KernelData getKernelData()
    {
        return kernel;
    }

    public DatabaseManager getDatabaseManager()
    {
        return databaseManager;
    }

    public <T> T resolveDependency( Class<T> clazz )
    {
        return databaseManager.getDatabaseFacade( DEFAULT_DATABASE_NAME )
                .orElseThrow( () -> new IllegalStateException( "Default database not found." ) )
                .getDependencyResolver().resolveDependency( clazz );
    }

    ObjectName getObjectName( String... extraNaming )
    {
        ObjectName name = support.createObjectName( kernel.instanceId(), provider.beanInterface, extraNaming );
        if ( name == null )
        {
            throw new IllegalArgumentException( provider.beanInterface
                                                + " is not a Neo4j Management Bean interface" );
        }
        return name;
    }

    void validate( Class<? extends Neo4jMBean> implClass )
    {
        if ( !provider.beanInterface.isAssignableFrom( implClass ) )
        {
            throw new IllegalStateException( implClass + " does not implement " + provider.beanInterface );
        }
    }
}
