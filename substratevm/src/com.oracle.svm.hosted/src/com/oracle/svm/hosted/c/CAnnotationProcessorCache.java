/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.c;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;
import static java.nio.file.FileVisitResult.CONTINUE;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import org.graalvm.compiler.options.Option;

import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.c.info.NativeCodeInfo;
import com.oracle.svm.hosted.c.query.QueryResultParser;

/**
 * Cache of pre-computed information for the {@link CAnnotationProcessor}. The cache is helpful to
 * cut substantially the time to build of an svm image, when many CAnnotation are being used. This
 * is the case, namely for Walnut, when the compilation time to generate the information for the C
 * processor is by far the dominant cost.
 *
 * For simplicity, the cache cannot be updated: it can be re-created from scratch, or used. The two
 * options are mutually exclusive, and the re-creation of the cache erase all previously cached
 * information. responsibility for the accuracy of the cached content is up to the end user.
 *
 * A CAP cache is just a directory with a file for each {@link NativeCodeInfo}, where the file is
 * pretty much the output of the "query" program generated by the CAnnotationProcessor.
 *
 */
public final class CAnnotationProcessorCache {
    private static final String FILE_EXTENSION = ".cap";

    public static class Options {
        @Option(help = "Indicate the C Annotation Processor to use previously cached native information when generating C Type information.")//
        public static final HostedOptionKey<Boolean> UseCAPCache = new HostedOptionKey<>(false);

        @Option(help = "Create a C Annotation Processor Cache. Will erase any previous cache at that same location.")//
        public static final HostedOptionKey<Boolean> NewCAPCache = new HostedOptionKey<>(false);

        @Option(help = "Directory where information generated by the CAnnotation Processor are cached.")//
        public static final HostedOptionKey<String> CAPCacheDir = new HostedOptionKey<>("");
    }

    private static File cache;

    public static synchronized void initialize() {
        if ((Options.UseCAPCache.getValue() || Options.NewCAPCache.getValue()) && cache == null) {
            if (Options.CAPCacheDir.getValue() == null || Options.CAPCacheDir.getValue().isEmpty()) {
                throw shouldNotReachHere("Path to CAnnotation Cache is not specified");
            }
            Path cachePath = FileSystems.getDefault().getPath(Options.CAPCacheDir.getValue()).toAbsolutePath();
            cache = cachePath.toFile();
            if (!cache.exists()) {
                try {
                    cache = Files.createDirectories(cachePath).toFile();
                } catch (IOException e) {
                    throw shouldNotReachHere("Path to CAnnotation Cache is not specified");
                }
            } else if (!cache.isDirectory()) {
                throw shouldNotReachHere("Path to CAnnotation Cache is not a directory");
            } else if (Options.NewCAPCache.getValue()) {
                clearCache();
            }
        }
    }

    private static String toPath(NativeCodeInfo nativeCodeInfo) {
        return nativeCodeInfo.getName().replaceAll("\\W", "_").concat(FILE_EXTENSION);
    }

    public static boolean get(NativeLibraries nativeLibs, NativeCodeInfo nativeCodeInfo) {
        if (Options.UseCAPCache.getValue()) {
            try (FileInputStream fis = new FileInputStream(new File(cache, toPath(nativeCodeInfo)))) {
                QueryResultParser.parse(nativeLibs, nativeCodeInfo, fis);
                return true;
            } catch (IOException e) {
            }
        }
        return false;
    }

    public static void put(NativeCodeInfo nativeCodeInfo, List<String> lines) {
        if (!Options.NewCAPCache.getValue()) {
            return;
        }
        File cachedItem = new File(cache, toPath(nativeCodeInfo));
        try (FileWriter writer = new FileWriter(cachedItem, true)) {
            for (String line : lines) {
                writer.write(line);
                writer.write(Character.LINE_SEPARATOR);
            }
            return;
        } catch (IOException e) {
            throw shouldNotReachHere("Invalid CAnnotation Processor Cache item:" + cachedItem.toString());
        }
    }

    private static void clearCache() {
        try {
            final Path cachePath = cache.toPath();
            Files.walkFileTree(cachePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    assert file.toString().endsWith(FILE_EXTENSION);
                    Files.delete(file);
                    return CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (!dir.equals(cachePath)) {
                        Files.delete(dir);
                    }
                    return CONTINUE;
                }
            });
        } catch (IOException eio) {
            throw shouldNotReachHere(eio);
        }
    }

    private CAnnotationProcessorCache() {
    }
}