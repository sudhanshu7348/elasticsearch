/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.fielddata;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.lucene.index.ElasticsearchDirectoryReader;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.fielddata.plain.ParentChildIndexFieldData;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParentFieldMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.mapper.UidFieldMapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.MultiValueMode;
import org.junit.Before;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class ParentChildFieldDataTests extends AbstractFieldDataTestCase {
    private final String parentType = "parent";
    private final String childType = "child";
    private final String grandChildType = "grand-child";

    @Before
    public void setupData() throws Exception {
        mapperService.merge(
                childType, new CompressedXContent(PutMappingRequest.buildFromSimplifiedDef(childType, "_parent", "type=" + parentType).string()), MapperService.MergeReason.MAPPING_UPDATE, false
        );
        mapperService.merge(
                grandChildType, new CompressedXContent(PutMappingRequest.buildFromSimplifiedDef(grandChildType, "_parent", "type=" + childType).string()), MapperService.MergeReason.MAPPING_UPDATE, false
        );

        Document d = new Document();
        d.add(new StringField(UidFieldMapper.NAME, Uid.createUid(parentType, "1"), Field.Store.NO));
        d.add(createJoinField(parentType, "1"));
        writer.addDocument(d);

        d = new Document();
        d.add(new StringField(UidFieldMapper.NAME, Uid.createUid(childType, "2"), Field.Store.NO));
        d.add(new StringField(ParentFieldMapper.NAME, Uid.createUid(parentType, "1"), Field.Store.NO));
        d.add(createJoinField(parentType, "1"));
        d.add(createJoinField(childType, "2"));
        writer.addDocument(d);
        writer.commit();

        d = new Document();
        d.add(new StringField(UidFieldMapper.NAME, Uid.createUid(childType, "3"), Field.Store.NO));
        d.add(new StringField(ParentFieldMapper.NAME, Uid.createUid(parentType, "1"), Field.Store.NO));
        d.add(createJoinField(parentType, "1"));
        d.add(createJoinField(childType, "3"));
        writer.addDocument(d);

        d = new Document();
        d.add(new StringField(UidFieldMapper.NAME, Uid.createUid(parentType, "2"), Field.Store.NO));
        d.add(createJoinField(parentType, "2"));
        writer.addDocument(d);

        d = new Document();
        d.add(new StringField(UidFieldMapper.NAME, Uid.createUid(childType, "4"), Field.Store.NO));
        d.add(new StringField(ParentFieldMapper.NAME, Uid.createUid(parentType, "2"), Field.Store.NO));
        d.add(createJoinField(parentType, "2"));
        d.add(createJoinField(childType, "4"));
        writer.addDocument(d);

        d = new Document();
        d.add(new StringField(UidFieldMapper.NAME, Uid.createUid(childType, "5"), Field.Store.NO));
        d.add(new StringField(ParentFieldMapper.NAME, Uid.createUid(parentType, "1"), Field.Store.NO));
        d.add(createJoinField(parentType, "1"));
        d.add(createJoinField(childType, "5"));
        writer.addDocument(d);
        writer.commit();

        d = new Document();
        d.add(new StringField(UidFieldMapper.NAME, Uid.createUid(grandChildType, "6"), Field.Store.NO));
        d.add(new StringField(ParentFieldMapper.NAME, Uid.createUid(childType, "2"), Field.Store.NO));
        d.add(createJoinField(childType, "2"));
        writer.addDocument(d);

        d = new Document();
        d.add(new StringField(UidFieldMapper.NAME, Uid.createUid("other-type", "1"), Field.Store.NO));
        writer.addDocument(d);
    }

    private SortedDocValuesField createJoinField(String parentType, String id) {
        return new SortedDocValuesField(ParentFieldMapper.joinField(parentType), new BytesRef(id));
    }

    public void testGetBytesValues() throws Exception {
        writer.forceMerge(1); // force merge to 1 segment so we can iterate through documents
        IndexFieldData indexFieldData = getForField(childType);
        List<LeafReaderContext> readerContexts = refreshReader();
        for (LeafReaderContext readerContext : readerContexts) {
            AtomicFieldData fieldData = indexFieldData.load(readerContext);

            SortedBinaryDocValues bytesValues = fieldData.getBytesValues();
            assertTrue(bytesValues.advanceExact(0));
            assertThat(bytesValues.docValueCount(), equalTo(1));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("1"));

            assertTrue(bytesValues.advanceExact(1));
            assertThat(bytesValues.docValueCount(), equalTo(2));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("1"));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("2"));

            assertTrue(bytesValues.advanceExact(2));
            assertThat(bytesValues.docValueCount(), equalTo(2));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("1"));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("3"));

            assertTrue(bytesValues.advanceExact(3));
            assertThat(bytesValues.docValueCount(), equalTo(1));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("2"));

            assertTrue(bytesValues.advanceExact(4));
            assertThat(bytesValues.docValueCount(), equalTo(2));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("2"));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("4"));

            assertTrue(bytesValues.advanceExact(5));
            assertThat(bytesValues.docValueCount(), equalTo(2));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("1"));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("5"));

            assertTrue(bytesValues.advanceExact(6));
            assertThat(bytesValues.docValueCount(), equalTo(1));
            assertThat(bytesValues.nextValue().utf8ToString(), equalTo("2"));

            assertFalse(bytesValues.advanceExact(7));
        }
    }

    public void testSorting() throws Exception {
        IndexFieldData indexFieldData = getForField(parentType);
        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(writer));
        SortField sortField = indexFieldData.sortField("_last", MultiValueMode.MIN, null, false);
        TopFieldDocs topDocs = searcher.search(new MatchAllDocsQuery(), 10, new Sort(sortField));
        assertThat(topDocs.totalHits, equalTo(8));
        assertThat(topDocs.scoreDocs.length, equalTo(8));
        assertThat(topDocs.scoreDocs[0].doc, equalTo(0));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[0]).fields[0]).utf8ToString(), equalTo("1"));
        assertThat(topDocs.scoreDocs[1].doc, equalTo(1));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[1]).fields[0]).utf8ToString(), equalTo("1"));
        assertThat(topDocs.scoreDocs[2].doc, equalTo(2));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[2]).fields[0]).utf8ToString(), equalTo("1"));
        assertThat(topDocs.scoreDocs[3].doc, equalTo(5));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[3]).fields[0]).utf8ToString(), equalTo("1"));
        assertThat(topDocs.scoreDocs[4].doc, equalTo(3));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[4]).fields[0]).utf8ToString(), equalTo("2"));
        assertThat(topDocs.scoreDocs[5].doc, equalTo(4));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[5]).fields[0]).utf8ToString(), equalTo("2"));
        assertThat(topDocs.scoreDocs[6].doc, equalTo(6));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[6]).fields[0]).utf8ToString(), equalTo("2"));
        assertThat(topDocs.scoreDocs[7].doc, equalTo(7));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[7]).fields[0]), equalTo(null));

        sortField = indexFieldData.sortField("_last", MultiValueMode.MIN, null, true);
        topDocs = searcher.search(new MatchAllDocsQuery(), 10, new Sort(sortField));
        assertThat(topDocs.totalHits, equalTo(8));
        assertThat(topDocs.scoreDocs.length, equalTo(8));
        assertThat(topDocs.scoreDocs[0].doc, equalTo(3));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[0]).fields[0]).utf8ToString(), equalTo("2"));
        assertThat(topDocs.scoreDocs[1].doc, equalTo(4));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[1]).fields[0]).utf8ToString(), equalTo("2"));
        assertThat(topDocs.scoreDocs[2].doc, equalTo(6));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[2]).fields[0]).utf8ToString(), equalTo("2"));
        assertThat(topDocs.scoreDocs[3].doc, equalTo(0));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[3]).fields[0]).utf8ToString(), equalTo("1"));
        assertThat(topDocs.scoreDocs[4].doc, equalTo(1));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[4]).fields[0]).utf8ToString(), equalTo("1"));
        assertThat(topDocs.scoreDocs[5].doc, equalTo(2));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[5]).fields[0]).utf8ToString(), equalTo("1"));
        assertThat(topDocs.scoreDocs[6].doc, equalTo(5));
        assertThat(((BytesRef) ((FieldDoc) topDocs.scoreDocs[6]).fields[0]).utf8ToString(), equalTo("1"));
        assertThat(topDocs.scoreDocs[7].doc, equalTo(7));
        assertThat(((FieldDoc) topDocs.scoreDocs[7]).fields[0], nullValue());
    }

    public void testThreads() throws Exception {
        final ParentChildIndexFieldData indexFieldData = getForField(childType);
        final DirectoryReader reader = ElasticsearchDirectoryReader.wrap(
                DirectoryReader.open(writer), new ShardId(new Index("test", ""), 0));
        final IndexParentChildFieldData global = indexFieldData.loadGlobal(reader);
        final AtomicReference<Exception> error = new AtomicReference<>();
        final int numThreads = scaledRandomIntBetween(3, 8);
        final Thread[] threads = new Thread[numThreads];
        final CountDownLatch latch = new CountDownLatch(1);

        final Map<Object, BytesRef[]> expected = new HashMap<>();
        for (LeafReaderContext context : reader.leaves()) {
            AtomicParentChildFieldData leafData = global.load(context);
            SortedDocValues parentIds = leafData.getOrdinalsValues(parentType);
            final BytesRef[] ids = new BytesRef[parentIds.getValueCount()];
            for (int j = 0; j < parentIds.getValueCount(); ++j) {
                final BytesRef id = parentIds.lookupOrd(j);
                if (id != null) {
                    ids[j] = BytesRef.deepCopyOf(id);
                }
            }
            expected.put(context.reader().getCoreCacheHelper().getKey(), ids);
        }

        for (int i = 0; i < numThreads; ++i) {
            threads[i] = new Thread() {
                @Override
                public void run() {
                    try {
                        latch.await();
                        for (int i = 0; i < 100000; ++i) {
                            for (LeafReaderContext context : reader.leaves()) {
                                AtomicParentChildFieldData leafData = global.load(context);
                                SortedDocValues parentIds = leafData.getOrdinalsValues(parentType);
                                final BytesRef[] expectedIds = expected.get(context.reader().getCoreCacheHelper().getKey());
                                for (int j = 0; j < parentIds.getValueCount(); ++j) {
                                    final BytesRef id = parentIds.lookupOrd(j);
                                    assertEquals(expectedIds[j], id);
                                }
                            }
                        }
                    } catch (Exception e) {
                        error.compareAndSet(null, e);
                    }
                }
            };
            threads[i].start();
        }
        latch.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        if (error.get() != null) {
            throw error.get();
        }
    }

    @Override
    protected String getFieldDataType() {
        return "_parent";
    }
}
