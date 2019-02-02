/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import nonapi.io.github.classgraph.utils.JarUtils;

/**
 * Information on the module path. Note that this will only include module system parameters actually listed in
 * commandline arguments -- in particular this does not include classpath elements from the traditional classpath,
 * or system modules.
 */
public class ModulePathInfo {
    /**
     * The module path provided on the commandline by the {@code --module-path} or {@code -p} switch, as an ordered
     * set of module names, in the order they were listed on the commandline.
     * 
     * <p>
     * Note that some modules (such as system modules) will not be in this list, as they are added to the module
     * system automatically by the runtime. Call {@link ClassGraph#getModules()} or {@link ScanResult#getModules()}
     * to get all modules visible at runtime.
     */
    public final Set<String> modulePath = new LinkedHashSet<>();

    /**
     * The modules added to the module path on the commandline using the {@code --add-modules} switch, as an ordered
     * set of module names, in the order they were listed on the commandline. Note that valid module names include
     * {@code ALL-DEFAULT}, {@code ALL-SYSTEM}, and {@code ALL-MODULE-PATH} (see
     * <a href="https://openjdk.java.net/jeps/261">JEP 261</a> for info).
     */
    public final Set<String> addModules = new LinkedHashSet<>();

    /**
     * The module patch directives listed on the commandline using the {@code --patch-modules} switch, as an ordered
     * set of strings in the format {code <module>=<file>}, in the order they were listed on the commandline.
     */
    public final Set<String> patchModules = new LinkedHashSet<>();

    /**
     * The module {@code exports} directives added on the commandline using the {@code --add-exports} switch, as an
     * ordered set of strings in the format {code <module>/<package>}, in the order they were listed on the
     * commandline. Additionally, if this {@link ModulePathInfo} object was obtained from
     * {@link ScanResult#getModulePathInfo()} rather than {@link ClassGraph#getModulePathInfo()}, any additional
     * {@code Add-Exports} entries found in manifest files during classpath scanning will be appended to this list.
     */
    public final Set<String> addExports = new LinkedHashSet<>();

    /**
     * The module {@code opens} directives added on the commandline using the {@code --add-opens} switch, as an
     * ordered set of strings in the format {code <module>/<package>}, in the order they were listed on the
     * commandline. Additionally, if this {@link ModulePathInfo} object was obtained from
     * {@link ScanResult#getModulePathInfo()} rather than {@link ClassGraph#getModulePathInfo()}, any additional
     * {@code Add-Opens} entries found in manifest files during classpath scanning will be appended to this list.
     */
    public final Set<String> addOpens = new LinkedHashSet<>();

    /**
     * The module {@code reads} directives added on the commandline using the {@code --add-reads} switch, as an
     * ordered set of strings in the format {code <source-module>=<target-module>}, in the order they were listed on
     * the commandline.
     */
    public final Set<String> addReads = new LinkedHashSet<>();

    private final List<Set<String>> fields = Arrays.asList(modulePath, addModules, patchModules, addExports,
            addOpens, addReads);
    private static final List<String> fieldSwitches = Arrays.asList("--module-path=", "--add-modules=",
            "--patch-module=", "--add-exports=", "--add-opens=", "--add-reads=");
    private static final List<Character> fieldSeparatorChars = Arrays.asList(File.pathSeparatorChar, ',',
            File.pathSeparatorChar, ',', ',', ',');

    /** Construct a {@link ModulePathInfo}. */
    public ModulePathInfo() {
        final List<String> commandlineArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (final String arg : commandlineArguments) {
            for (int i = 0; i < fields.size(); i++) {
                final String fieldSwitch = fieldSwitches.get(i);
                if (arg.startsWith(fieldSwitch)) {
                    final Set<String> argField = fields.get(i);
                    for (final String argPart : JarUtils.smartPathSplit(arg.substring(fieldSwitch.length()),
                            fieldSeparatorChars.get(i))) {
                        argField.add(argPart);
                    }
                }
            }
        }
    }
}