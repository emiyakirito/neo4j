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
package org.neo4j.kernel.impl.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.impl.store.allocator.ReusableRecordsCompositeAllocator;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;

import static java.lang.String.format;
import static org.neo4j.kernel.impl.store.AbstractDynamicStore.readFullByteArrayFromHeavyRecords;
import static org.neo4j.kernel.impl.store.DynamicArrayStore.getRightArray;
import static org.neo4j.kernel.impl.store.LabelIdArray.filter;
import static org.neo4j.kernel.impl.store.LabelIdArray.stripNodeId;
import static org.neo4j.kernel.impl.store.NodeLabelsField.fieldPointsToDynamicRecordOfLabels;
import static org.neo4j.kernel.impl.store.NodeLabelsField.firstDynamicLabelRecordId;
import static org.neo4j.kernel.impl.store.NodeLabelsField.parseLabelsBody;
import static org.neo4j.kernel.impl.store.PropertyType.ARRAY;

public class DynamicNodeLabels implements NodeLabels
{
    private final NodeRecord node;

    public DynamicNodeLabels( NodeRecord node )
    {
        this.node = node;
    }

    @Override
    public long[] get( NodeStore nodeStore, PageCursorTracer cursorTracer )
    {
        return get( node, nodeStore, cursorTracer );
    }

    public static long[] get( NodeRecord node, NodeStore nodeStore, PageCursorTracer cursorTracer )
    {
        if ( node.isLight() )
        {
            nodeStore.ensureHeavy( node, firstDynamicLabelRecordId( node.getLabelField() ), cursorTracer );
        }
        return getDynamicLabelsArray( node.getUsedDynamicLabelRecords(), nodeStore.getDynamicLabelStore(), cursorTracer );
    }

    public static boolean hasLabel( NodeRecord node, NodeStore nodeStore, PageCursorTracer cursorTracer, int label )
    {
        DynamicArrayStore dynamicLabelStore = nodeStore.getDynamicLabelStore();
        HasLabelSubscriber subscriber = new HasLabelSubscriber( label, dynamicLabelStore, cursorTracer );
        if ( node.isLight() )
        {
            //dynamic records not there, stream the result from the dynamic label store
            dynamicLabelStore.streamRecords( firstDynamicLabelRecordId( node.getLabelField() ), RecordLoad.NORMAL, false, cursorTracer, subscriber );
        }
        else
        {
            //dynamic records are already here, lets use them
            for ( DynamicRecord record : node.getUsedDynamicLabelRecords() )
            {
                if ( !subscriber.onRecord( record ) )
                {
                    break;
                }
            }
        }
        return subscriber.hasLabel();
    }

    @Override
    public long[] getIfLoaded()
    {
        if ( node.isLight() )
        {
            return null;
        }
        return stripNodeId( (long[]) getRightArray( readFullByteArrayFromHeavyRecords(
                node.getUsedDynamicLabelRecords(), ARRAY ) ).asObject() );
    }

    @Override
    public Collection<DynamicRecord> put( long[] labelIds, NodeStore nodeStore, DynamicRecordAllocator allocator, PageCursorTracer cursorTracer )
    {
        Arrays.sort( labelIds );
        return putSorted( node, labelIds, nodeStore, allocator, cursorTracer );
    }

    static Collection<DynamicRecord> putSorted( NodeRecord node, long[] labelIds, NodeStore nodeStore, DynamicRecordAllocator allocator,
            PageCursorTracer cursorTracer )
    {
        long existingLabelsField = node.getLabelField();
        long existingLabelsBits = parseLabelsBody( existingLabelsField );

        Collection<DynamicRecord> changedDynamicRecords = node.getDynamicLabelRecords();

        long labelField = node.getLabelField();
        if ( fieldPointsToDynamicRecordOfLabels( labelField ) )
        {
            // There are existing dynamic label records, get them
            nodeStore.ensureHeavy( node, existingLabelsBits, cursorTracer );
            changedDynamicRecords = node.getDynamicLabelRecords();
            setNotInUse( changedDynamicRecords );
        }

        if ( !InlineNodeLabels.tryInlineInNodeRecord( node, labelIds, changedDynamicRecords ) )
        {
            Iterator<DynamicRecord> recycledRecords = changedDynamicRecords.iterator();
            Collection<DynamicRecord> allocatedRecords = allocateRecordsForDynamicLabels( node.getId(), labelIds,
                    new ReusableRecordsCompositeAllocator( recycledRecords, allocator ), cursorTracer );
            // Set the rest of the previously set dynamic records as !inUse
            while ( recycledRecords.hasNext() )
            {
                DynamicRecord removedRecord = recycledRecords.next();
                removedRecord.setInUse( false );
                allocatedRecords.add( removedRecord );
            }
            node.setLabelField( dynamicPointer( allocatedRecords ), allocatedRecords );
            changedDynamicRecords = allocatedRecords;
        }

        return changedDynamicRecords;
    }

    @Override
    public Collection<DynamicRecord> add( long labelId, NodeStore nodeStore, DynamicRecordAllocator allocator, PageCursorTracer cursorTracer )
    {
        nodeStore.ensureHeavy( node, firstDynamicLabelRecordId( node.getLabelField() ), cursorTracer );
        long[] existingLabelIds = getDynamicLabelsArray( node.getUsedDynamicLabelRecords(),
                nodeStore.getDynamicLabelStore(), cursorTracer );
        long[] newLabelIds = LabelIdArray.concatAndSort( existingLabelIds, labelId );
        Collection<DynamicRecord> existingRecords = node.getDynamicLabelRecords();
        Collection<DynamicRecord> changedDynamicRecords = allocateRecordsForDynamicLabels( node.getId(), newLabelIds,
                new ReusableRecordsCompositeAllocator( existingRecords, allocator ), cursorTracer );
        node.setLabelField( dynamicPointer( changedDynamicRecords ), changedDynamicRecords );
        return changedDynamicRecords;
    }

    @Override
    public Collection<DynamicRecord> remove( long labelId, NodeStore nodeStore, PageCursorTracer cursorTracer )
    {
        nodeStore.ensureHeavy( node, firstDynamicLabelRecordId( node.getLabelField() ), cursorTracer );
        long[] existingLabelIds = getDynamicLabelsArray( node.getUsedDynamicLabelRecords(),
                nodeStore.getDynamicLabelStore(), cursorTracer );
        long[] newLabelIds = filter( existingLabelIds, labelId );
        Collection<DynamicRecord> existingRecords = node.getDynamicLabelRecords();
        if ( InlineNodeLabels.tryInlineInNodeRecord( node, newLabelIds, existingRecords ) )
        {
            setNotInUse( existingRecords );
        }
        else
        {
            Collection<DynamicRecord> newRecords = allocateRecordsForDynamicLabels( node.getId(), newLabelIds,
                    new ReusableRecordsCompositeAllocator( existingRecords, nodeStore.getDynamicLabelStore() ), cursorTracer );
            node.setLabelField( dynamicPointer( newRecords ), existingRecords );
            if ( !newRecords.equals( existingRecords ) )
            {   // One less dynamic record, mark that one as not in use
                for ( DynamicRecord record : existingRecords )
                {
                    if ( !newRecords.contains( record ) )
                    {
                        record.setInUse( false );
                    }
                }
            }
        }
        return existingRecords;
    }

    public long getFirstDynamicRecordId()
    {
        return firstDynamicLabelRecordId( node.getLabelField() );
    }

    public static long dynamicPointer( Collection<DynamicRecord> newRecords )
    {
        return dynamicPointer( Iterables.first( newRecords ).getId() );
    }

    public static long dynamicPointer( long dynamicRecordId )
    {
        return 0x8000000000L | dynamicRecordId;
    }

    private static void setNotInUse( Collection<DynamicRecord> changedDynamicRecords )
    {
        for ( DynamicRecord record : changedDynamicRecords )
        {
            record.setInUse( false );
        }
    }

    @Override
    public boolean isInlined()
    {
        return false;
    }

    @Override
    public String toString()
    {
        if ( node.isLight() )
        {
            return format( "Dynamic(id:%d)", firstDynamicLabelRecordId( node.getLabelField() ) );
        }
        return format( "Dynamic(id:%d,[%s])", firstDynamicLabelRecordId( node.getLabelField() ),
                Arrays.toString( getDynamicLabelsArrayFromHeavyRecords( node.getUsedDynamicLabelRecords() ) ) );
    }

    public static Collection<DynamicRecord> allocateRecordsForDynamicLabels( long nodeId, long[] labels,
            AbstractDynamicStore dynamicLabelStore, PageCursorTracer cursorTracer )
    {
        return allocateRecordsForDynamicLabels( nodeId, labels, (DynamicRecordAllocator)dynamicLabelStore, cursorTracer );
    }

    public static Collection<DynamicRecord> allocateRecordsForDynamicLabels( long nodeId, long[] labels,
            DynamicRecordAllocator allocator, PageCursorTracer cursorTracer )
    {
        long[] storedLongs = LabelIdArray.prependNodeId( nodeId, labels );
        Collection<DynamicRecord> records = new ArrayList<>();
        // since we can't store points in long array we passing false as possibility to store points
        DynamicArrayStore.allocateRecords( records, storedLongs, allocator, false, cursorTracer );
        return records;
    }

    public static long[] getDynamicLabelsArray( Iterable<DynamicRecord> records, AbstractDynamicStore dynamicLabelStore, PageCursorTracer cursorTracer )
    {
        long[] storedLongs = (long[])
            DynamicArrayStore.getRightArray( dynamicLabelStore.readFullByteArray( records, PropertyType.ARRAY, cursorTracer ) ).asObject();
        return LabelIdArray.stripNodeId( storedLongs );
    }

    public static long[] getDynamicLabelsArrayFromHeavyRecords( Iterable<DynamicRecord> records )
    {
        long[] storedLongs = (long[])
            DynamicArrayStore.getRightArray( readFullByteArrayFromHeavyRecords( records, PropertyType.ARRAY ) ).asObject();
        return LabelIdArray.stripNodeId( storedLongs );
    }

    public static Pair<Long, long[]> getDynamicLabelsArrayAndOwner( Iterable<DynamicRecord> records,
            AbstractDynamicStore dynamicLabelStore, PageCursorTracer cursorTracer )
    {
        long[] storedLongs = (long[])
                DynamicArrayStore.getRightArray( dynamicLabelStore.readFullByteArray( records, PropertyType.ARRAY, cursorTracer ) ).asObject();
        return Pair.of(storedLongs[0], LabelIdArray.stripNodeId( storedLongs ));
    }
}
