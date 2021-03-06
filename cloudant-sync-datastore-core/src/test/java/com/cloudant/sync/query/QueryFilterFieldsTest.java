//  Copyright (c) 2015 Cloudant. All rights reserved.
//
//  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
//  except in compliance with the License. You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software distributed under the
//  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
//  either express or implied. See the License for the specific language governing permissions
//  and limitations under the License.

package com.cloudant.sync.query;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.cloudant.sync.datastore.BasicDocumentRevision;
import com.cloudant.sync.datastore.ConflictException;
import com.cloudant.sync.datastore.DocumentBodyFactory;
import com.cloudant.sync.datastore.DocumentRevision;
import com.cloudant.sync.datastore.MutableDocumentRevision;
import com.cloudant.sync.datastore.ProjectedDocumentRevision;
import com.cloudant.sync.util.SQLDatabaseTestUtils;
import com.cloudant.sync.util.TestUtils;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class QueryFilterFieldsTest extends AbstractQueryTestBase {

    @Override
    public void setUp() throws Exception {
        super.setUp();
        im = new IndexManager(ds);
        assertThat(im, is(notNullValue()));
        db = TestUtils.getDatabaseConnectionToExistingDb(im.getDatabase());
        assertThat(db, is(notNullValue()));
        assertThat(im.getQueue(), is(notNullValue()));
        String[] metadataTableList = new String[] { IndexManager.INDEX_METADATA_TABLE_NAME };
        SQLDatabaseTestUtils.assertTablesExist(db, metadataTableList);

        setUpBasicQueryData();
    }

    // When filtering fields on find

    @Test
    public void returnsFieldSpecifiedOnly() {
        // query - { "name" : "mike" }
        Map<String, Object> query = new HashMap<String, Object>();
        query.put("name", "mike");
        QueryResult queryResult = im.find(query, 0, Long.MAX_VALUE, Arrays.asList("name"), null);
        for (DocumentRevision rev : queryResult) {
            Map<String, Object> revBody = rev.getBody().asMap();
            assertThat(revBody.keySet(), contains("name"));
        }
    }

    @Test
    public void returnsAllFieldsWhenFieldsArrayEmpty() {
        // query - { "name" : "mike" }
        Map<String, Object> query = new HashMap<String, Object>();
        query.put("name", "mike");
        QueryResult queryResult = im.find(query, 0, Long.MAX_VALUE, new ArrayList<String>(), null);
        for (DocumentRevision rev : queryResult) {
            Map<String, Object> revBody = rev.getBody().asMap();
            assertThat(revBody.keySet(), containsInAnyOrder("name", "pet", "age"));
        }
    }

    @Test
    public void returnsAllFieldsWhenFieldsArrayNull() {
        // query - { "name" : "mike" }
        Map<String, Object> query = new HashMap<String, Object>();
        query.put("name", "mike");
        QueryResult queryResult = im.find(query, 0, Long.MAX_VALUE, null, null);
        for (DocumentRevision rev : queryResult) {
            Map<String, Object> revBody = rev.getBody().asMap();
            assertThat(revBody.keySet(), containsInAnyOrder("name", "pet", "age"));
        }
    }

    @Test
    public void returnsNullWhenUsingDottedNotation() {
        // query - { "name" : "mike" }
        Map<String, Object> query = new HashMap<String, Object>();
        query.put("name", "mike");
        QueryResult queryResult = im.find(query,
                                          0,
                                          Long.MAX_VALUE,
                                          Arrays.asList("name.blah"),
                                          null);
        assertThat(queryResult, is(nullValue()));
    }

    @Test
    public void returnsOnlyFieldsSpecified() {
        // query - { "name" : "mike" }
        Map<String, Object> query = new HashMap<String, Object>();
        query.put("name", "mike");
        QueryResult queryResult = im.find(query,
                                          0,
                                          Long.MAX_VALUE,
                                          Arrays.asList("name", "pet"),
                                          null);
        for (DocumentRevision rev : queryResult) {
            Map<String, Object> revBody = rev.getBody().asMap();
            assertThat(revBody.keySet(), containsInAnyOrder("name", "pet"));
        }
    }

    @Test
    public void returnsFullMutableCopyOfProjectedDoc() {
        // query - { "name" : "mike", "age" : 12 }
        Map<String, Object> query = new HashMap<String, Object>();
        query.put("name", "mike");
        query.put("age", 12);
        QueryResult queryResult = im.find(query, 0, Long.MAX_VALUE, Arrays.asList("name"), null);
        assertThat(queryResult.size(), is(1));
        for (DocumentRevision rev : queryResult) {
            Map<String, Object> revBody = rev.getBody().asMap();
            assertThat(revBody.keySet(), contains("name"));
            assertThat((String) revBody.get("name"), is("mike"));

            assertThat(rev, instanceOf(ProjectedDocumentRevision.class));
            MutableDocumentRevision mutable = ((ProjectedDocumentRevision) rev).mutableCopy();
            Map<String, Object> mutableBody = mutable.getBody().asMap();
            assertThat(mutableBody.keySet(), containsInAnyOrder("name", "age", "pet"));
            assertThat((String) mutableBody.get("name"), is("mike"));
            assertThat((Integer) mutableBody.get("age"), is(12));
            assertThat((String) mutableBody.get("pet"), is("cat"));
        }
    }

    @Test
    public void returnsNullMutableCopyWhenDocUpdated() throws Exception {
        // query - { "name" : "mike", "age" : 12 }
        Map<String, Object> query = new HashMap<String, Object>();
        query.put("name", "mike");
        query.put("age", 12);
        QueryResult queryResult = im.find(query, 0, Long.MAX_VALUE, Arrays.asList("name"), null);
        assertThat(queryResult.size(), is(1));
        for (DocumentRevision rev : queryResult) {
            assertThat(rev, is(instanceOf(ProjectedDocumentRevision.class)));
            Map<String, Object> revBody = rev.getBody().asMap();
            assertThat(revBody.keySet(), contains("name"));
            assertThat((String) revBody.get("name"), is("mike"));

            BasicDocumentRevision original = ds.getDocument(rev.getId());
            MutableDocumentRevision update = original.mutableCopy();
            Map<String, Object> updateBody = original.getBody().asMap();
            updateBody.put("name", "charles");
            update.body = DocumentBodyFactory.create(updateBody);
            assertThat(ds.updateDocumentFromRevision(update), is(notNullValue()));
            assertThat(((ProjectedDocumentRevision) rev).mutableCopy(), is(nullValue()));
        }
    }

    @Test
    public void returnsNullMutableCopyWhenDocDeleted() {
        // query - { "name" : "mike", "age" : 12 }
        Map<String, Object> query = new HashMap<String, Object>();
        query.put("name", "mike");
        query.put("age", 12);
        QueryResult queryResult = im.find(query, 0, Long.MAX_VALUE, Arrays.asList("name"), null);
        assertThat(queryResult.size(), is(1));
        for (DocumentRevision rev : queryResult) {
            assertThat(rev, is(instanceOf(ProjectedDocumentRevision.class)));
            Map<String, Object> revBody = rev.getBody().asMap();
            assertThat(revBody.keySet(), contains("name"));
            assertThat((String) revBody.get("name"), is("mike"));

            try {
                DocumentRevision deleted;
                deleted = ds.deleteDocumentFromRevision((ProjectedDocumentRevision) rev);
                assertThat(deleted, is(notNullValue()));
            } catch (ConflictException e) {
                Assert.fail("Failed to delete document revision");
                e.printStackTrace();
            }

            assertThat(((ProjectedDocumentRevision) rev).mutableCopy(), is(nullValue()));
        }
    }

}
