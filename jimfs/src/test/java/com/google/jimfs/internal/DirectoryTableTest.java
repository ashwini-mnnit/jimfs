/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jimfs.internal;

import static com.google.jimfs.internal.Name.PARENT;
import static com.google.jimfs.internal.Name.SELF;
import static org.junit.Assert.fail;
import static org.truth0.Truth.ASSERT;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.jimfs.Normalization;

import org.junit.Before;
import org.junit.Test;

import javax.annotation.Nullable;

/**
 * Tests for {@link DirectoryTable}.
 *
 * @author Colin Decker
 */
public class DirectoryTableTest {

  private File rootFile;
  private File dirFile;

  private DirectoryTable root;
  private DirectoryTable table;

  @Before
  public void setUp() {
    DirectoryTable superRootTable = new DirectoryTable();
    File superRoot = new File(-1, superRootTable);
    superRootTable.setSuperRoot(superRoot);

    root = new DirectoryTable();
    rootFile = new File(0L, root);

    superRootTable.link(Name.simple("/"), rootFile);
    root.setRoot();

    table = new DirectoryTable();
    dirFile = new File(1L, table);
    root.link(Name.simple("foo"), dirFile);
  }

  @SuppressWarnings("ConstantConditions")
  @Test
  public void testRootDirectory() {
    ASSERT.that(root.size()).is(3); // two for parent/self, one for table
    ASSERT.that(root.isEmpty()).isFalse();
    ASSERT.that(root.entry()).is(entry(rootFile, "/", rootFile));
    ASSERT.that(root.name()).is(Name.simple("/"));

    assertParentAndSelf(root, rootFile, rootFile);
  }

  @Test
  public void testEmptyDirectory() {
    ASSERT.that(table.size()).is(2);
    ASSERT.that(table.isEmpty()).isTrue();

    assertParentAndSelf(table, rootFile, dirFile);
  }

  @Test
  public void testGet() {
    ASSERT.that(root.get(Name.simple("foo"))).is(entry(rootFile, "foo", dirFile));
    ASSERT.that(table.get(Name.simple("foo"))).isNull();
    ASSERT.that(root.get(Name.simple("Foo"))).isNull();
  }

  @Test
  public void testLink() {
    ASSERT.that(table.get(Name.simple("bar"))).isNull();

    File bar = new File(2L, new DirectoryTable());
    table.link(Name.simple("bar"), bar);

    ASSERT.that(table.get(Name.simple("bar"))).is(entry(dirFile, "bar", bar));
  }

  @Test
  public void testLink_existingNameFails() {
    try {
      root.link(Name.simple("foo"), new File(2L, new DirectoryTable()));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testLink_parentAndSelfNameFails() {
    try {
      table.link(Name.simple("."), new File(2L, new DirectoryTable()));
      fail();
    } catch (IllegalArgumentException expected) {
    }

    try {
      table.link(Name.simple(".."), new File(2L, new DirectoryTable()));
      fail();
    } catch (IllegalArgumentException expected) {
    }

    // ensure that even if the parent/self entries do not already exist in the table,
    // they aren't allowed when calling link()
    File file = new File(2L, new DirectoryTable());

    try {
      file.asDirectoryTable().link(Name.simple("."), new File(2L, new DirectoryTable()));
      fail();
    } catch (IllegalArgumentException expected) {
    }

    try {
      file.asDirectoryTable().link(Name.simple(".."), new File(2L, new DirectoryTable()));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testGet_normalizingCaseInsensitive() {
    File bar = new File(2L, new DirectoryTable());
    Name barName = caseInsensitive("bar");

    table.link(barName, bar);

    DirectoryEntry expected = new DirectoryEntry(dirFile, barName, bar);
    ASSERT.that(table.get(caseInsensitive("bar"))).is(expected);
    ASSERT.that(table.get(caseInsensitive("BAR"))).is(expected);
    ASSERT.that(table.get(caseInsensitive("Bar"))).is(expected);
    ASSERT.that(table.get(caseInsensitive("baR"))).is(expected);
  }

  @Test
  public void testUnlink() {
    ASSERT.that(root.get(Name.simple("foo"))).isNotNull();

    root.unlink(Name.simple("foo"));

    ASSERT.that(root.get(Name.simple("foo"))).isNull();
  }

  @Test
  public void testUnlink_nonExistentNameFails() {
    try {
      table.unlink(Name.simple("bar"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testUnlink_parentAndSelfNameFails() {
    try {
      table.unlink(Name.simple("."));
      fail();
    } catch (IllegalArgumentException expected) {
    }

    try {
      table.unlink(Name.simple(".."));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testUnlink_normalizingCaseInsensitive() {
    table.link(caseInsensitive("bar"), new File(2L, new DirectoryTable()));

    ASSERT.that(table.get(caseInsensitive("bar"))).isNotNull();

    table.unlink(caseInsensitive("BAR"));

    ASSERT.that(table.get(caseInsensitive("bar"))).isNull();
  }

  @Test
  public void testLinkDirectory() {
    DirectoryTable newTable = new DirectoryTable();
    File newDir = new File(10, newTable);

    ASSERT.that(newTable.entry()).isNull();
    ASSERT.that(newTable.get(Name.SELF)).isNull();
    ASSERT.that(newTable.get(Name.PARENT)).isNull();
    ASSERT.that(newDir.links()).is(0);

    table.link(Name.simple("foo"), newDir);

    ASSERT.that(newTable.entry()).is(entry(dirFile, "foo", newDir));
    ASSERT.that(newTable.parent()).is(dirFile);
    ASSERT.that(newTable.name()).is(Name.simple("foo"));
    ASSERT.that(newTable.self()).is(newDir);
    ASSERT.that(newTable.get(Name.SELF)).is(entry(newDir, ".", newDir));
    ASSERT.that(newTable.get(Name.PARENT)).is(entry(newDir, "..", dirFile));
    ASSERT.that(newDir.links()).is(2);
  }

  @Test
  public void testUnlinkDirectory() {
    DirectoryTable newTable = new DirectoryTable();
    File newDir = new File(10, newTable);

    table.link(Name.simple("foo"), newDir);

    ASSERT.that(newTable.entry()).is(entry(dirFile, "foo", newDir));
    ASSERT.that(newDir.links()).is(2);

    table.unlink(Name.simple("foo"));

    ASSERT.that(newTable.entry()).isNull();
    ASSERT.that(newTable.get(Name.SELF)).isNull();
    ASSERT.that(newTable.get(Name.PARENT)).isNull();
    ASSERT.that(newDir.links()).is(0);
  }

  @Test
  public void testSnapshot() {
    root.link(Name.simple("bar"), new File(2L, new StubByteStore(10)));
    root.link(Name.simple("abc"), new File(3L, new StubByteStore(10)));

    // does not include . or .. and is sorted by the name
    ASSERT.that(root.snapshot())
        .has().exactly(Name.simple("abc"), Name.simple("bar"), Name.simple("foo"));
  }

  @Test
  public void testSnapshot_sortsUsingStringAndNotCanonicalValueOfNames() {
    table.link(caseInsensitive("FOO"), new File(2L, new StubByteStore(10)));
    table.link(caseInsensitive("bar"), new File(3L, new StubByteStore(10)));

    ImmutableSortedSet<Name> snapshot = table.snapshot();
    Iterable<String> strings = Iterables.transform(snapshot, Functions.toStringFunction());

    // "FOO" comes before "bar"
    // if the order were based on the normalized, canonical form of the names ("foo" and "bar"),
    // "bar" would come first
    ASSERT.that(strings).iteratesAs("FOO", "bar");
  }

  private static DirectoryEntry entry(File dir, String name, @Nullable File file) {
    return new DirectoryEntry(dir, Name.simple(name), file);
  }

  @SuppressWarnings("ConstantConditions")
  private static void assertParentAndSelf(DirectoryTable table, File parent, File self) {
    ASSERT.that(table.get(PARENT)).is(entry(self, "..", parent));
    ASSERT.that(table.parent()).is(parent);

    ASSERT.that(table.get(SELF)).is(entry(self, ".", self));
    ASSERT.that(table.self()).is(self);
  }

  private static Name caseInsensitive(String name) {
    return Name.create(name, Normalization.CASE_FOLD.normalize(name));
  }
}
