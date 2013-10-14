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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.jimfs.PathType.ParseResult;

import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.jimfs.Configuration;
import com.google.jimfs.PathType;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Service for creating {@link JimfsPath} instances and handling other path-related operations.
 *
 * @author Colin Decker
 */
final class PathService implements Comparator<JimfsPath> {

  private static final Ordering<Name> DISPLAY_ROOT_ORDERING =
      Ordering.usingToString().nullsLast();
  private static final Ordering<Iterable<Name>> DISPLAY_NAMES_ORDERING =
      Ordering.usingToString().lexicographical();

  private static final Ordering<Name> CANONICAL_ROOT_ORDERING =
      Ordering.natural().nullsLast();
  private static final Ordering<Iterable<Name>> CANONICAL_NAMES_ORDERING =
      Ordering.natural().lexicographical();

  private final PathType type;

  private final PathNormalizer displayNormalizer;
  private final PathNormalizer canonicalNormalizer;
  private final boolean equalityUsesCanonicalForm;

  private final Ordering<Name> rootOrdering;
  private final Ordering<Iterable<Name>> namesOrdering;

  private volatile FileSystem fileSystem;
  private volatile JimfsPath emptyPath;

  PathService(Configuration config) {
    this(config.getPathType(),
        PathNormalizer.create(config.getPathDisplayNormalization()),
        PathNormalizer.create(config.getPathCanonicalNormalization()),
        config.getPathEqualityUsesCanonicalForm());
  }

  PathService(PathType type,
      PathNormalizer displayNormalizer, PathNormalizer canonicalNormalizer,
      boolean equalityUsesCanonicalForm) {
    this.type = checkNotNull(type);
    this.displayNormalizer = checkNotNull(displayNormalizer);
    this.canonicalNormalizer = checkNotNull(canonicalNormalizer);
    this.equalityUsesCanonicalForm = equalityUsesCanonicalForm;

    if (equalityUsesCanonicalForm) {
      rootOrdering = CANONICAL_ROOT_ORDERING;
      namesOrdering = CANONICAL_NAMES_ORDERING;
    } else {
      rootOrdering = DISPLAY_ROOT_ORDERING;
      namesOrdering = DISPLAY_NAMES_ORDERING;
    }
  }

  /**
   * Sets the file system to use for created paths.
   */
  public void setFileSystem(FileSystem fileSystem) {
    // allowed to not be JimfsFileSystem for testing purposes only
    checkState(this.fileSystem == null, "may not set fileSystem twice");
    this.fileSystem = checkNotNull(fileSystem);
  }

  /**
   * Returns the file system this service is for.
   */
  public FileSystem getFileSystem() {
    return fileSystem;
  }

  /**
   * Returns the default path separator.
   */
  public String getSeparator() {
    return type.getSeparator();
  }

  /**
   * Returns an empty path which has a single name, the empty string.
   */
  public final JimfsPath emptyPath() {
    JimfsPath result = emptyPath;
    if (result == null) {
      // use createPathInternal to avoid recursive call from createPath()
      result = createPathInternal(null, ImmutableList.of(Name.EMPTY));
      emptyPath = result;
      return result;
    }
    return result;
  }

  /**
   * Returns the {@link Name} form of the given string.
   */
  public Name name(String name) {
    switch (name) {
      case "":
        return Name.EMPTY;
      case ".":
        return Name.SELF;
      case "..":
        return Name.PARENT;
    }

    String display = displayNormalizer.normalize(name);
    String canonical = canonicalNormalizer.normalize(name);
    return Name.create(display, canonical);
  }

  /**
   * Returns the {@link Name} forms of the given strings.
   */
  public Iterable<Name> names(Iterable<String> names) {
    List<Name> result = new ArrayList<>();
    for (String name : names) {
      result.add(name(name));
    }
    return result;
  }

  /**
   * Returns a root path with the given name.
   */
  public final JimfsPath createRoot(Name root) {
    return createPath(checkNotNull(root), ImmutableList.<Name>of());
  }

  /**
   * Returns a single filename path with the given name.
   */
  public final JimfsPath createFileName(Name name) {
    return createPath(null, ImmutableList.of(name));
  }

  /**
   * Returns a relative path with the given names.
   */
  public final JimfsPath createRelativePath(Iterable<Name> names) {
    return createPath(null, ImmutableList.copyOf(names));
  }

  /**
   * Returns a path with the given root (or no root, if null) and the given names.
   */
  public final JimfsPath createPath(@Nullable Name root, Iterable<Name> names) {
    ImmutableList<Name> nameList = ImmutableList.copyOf(Iterables.filter(names, NOT_EMPTY));
    if (root == null && nameList.isEmpty()) {
      // ensure the canonical empty path (one empty string name) is used rather than a path with
      // no root and no names
      return emptyPath();
    }
    return createPathInternal(root, nameList);
  }

  /**
   * Returns a path with the given root (or no root, if null) and the given names.
   */
  protected final JimfsPath createPathInternal(@Nullable Name root, Iterable<Name> names) {
    return new JimfsPath(this, root, names);
  }

  /**
   * Parses the given strings as a path.
   */
  public final JimfsPath parsePath(String first, String... more) {
    String joined = type.joiner()
        .join(Iterables.filter(Lists.asList(first, more), NOT_EMPTY));
    return toPath(type.parsePath(joined));
  }

  private JimfsPath toPath(ParseResult parsed) {
    Name root = parsed.root() == null ? null : name(parsed.root());
    Iterable<Name> names = names(parsed.names());
    return createPath(root, names);
  }

  /**
   * Returns the string form of the given path.
   */
  public final String toString(JimfsPath path) {
    Name root = path.root();
    String rootString = root == null ? null : root.toString();
    Iterable<String> names = Iterables.transform(path.names(), Functions.toStringFunction());
    return type.toString(rootString, names);
  }

  /**
   * Creates a hash code for the given path.
   */
  public int hash(JimfsPath path) {
    int hash = 31;
    hash = 31 * hash + getFileSystem().hashCode();

    final Name root = path.root();
    final ImmutableList<Name> names = path.names();

    if (equalityUsesCanonicalForm) {
      // use hash codes of names themselves, which are based on the canonical form
      hash = 31 * hash + (root == null ? 0 : root.hashCode());
      for (Name name : names) {
        hash = 31 * hash + name.hashCode();
      }
    } else {
      // use hash codes from toString() form of names
      hash = 31 * hash + (root == null ? 0 : root.toString().hashCode());
      for (Name name : names) {
        hash = 31 * hash + name.toString().hashCode();
      }
    }
    return hash;
  }

  @Override
  public int compare(JimfsPath a, JimfsPath b) {
    return ComparisonChain.start()
        .compare(a.root(), b.root(), rootOrdering)
        .compare(a.names(), b.names(), namesOrdering)
        .result();
  }

  /**
   * Returns the URI for the given path. The given file system URI is the base against which the
   * path is resolved to create the returned URI.
   */
  public final URI toUri(URI fileSystemUri, JimfsPath path) {
    checkArgument(path.isAbsolute(), "path (%s) must be absolute", path);
    String root = String.valueOf(path.root());
    Iterable<String> names = Iterables.transform(path.names(), Functions.toStringFunction());
    return type.toUri(fileSystemUri, root, names);
  }

  /**
   * Converts the path of the given URI into a path for this file system.
   */
  public final JimfsPath fromUri(URI uri) {
    return toPath(type.fromUri(uri));
  }

  /**
   * Returns a {@link PathMatcher} for the given syntax and pattern as specified by
   * {@link FileSystem#getPathMatcher(String)}.
   */
  public final PathMatcher createPathMatcher(String syntaxAndPattern) {
    return PathMatchers.getPathMatcher(
        syntaxAndPattern, type.getSeparator() + type.getOtherSeparators());
  }

  private static final Predicate<Object> NOT_EMPTY = new Predicate<Object>() {
    @Override
    public boolean apply(Object input) {
      return !input.toString().isEmpty();
    }
  };
}
