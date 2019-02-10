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
 * Copyright (c) 2019 Luke Hutchison
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

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.Scanner.ClassfileScanWorkUnit;
import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.exceptions.ParseException;
import nonapi.io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.Join;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A classfile binary format parser. Implements its own buffering to avoid the overhead of using DataInputStream.
 * This class should only be used by a single thread at a time, but can be re-used to scan multiple classfiles in
 * sequence, to avoid re-allocating buffer memory.
 */
class Classfile {
    /** The InputStream or ByteBuffer for the current classfile. */
    private InputStreamOrByteBufferAdapter inputStreamOrByteBuffer;

    /** The classpath element that contains this classfile. */
    private final ClasspathElement classpathElement;

    /** The classpath order. */
    private final List<ClasspathElement> classpathOrder;

    /** The relative path to the classfile (should correspond to className). */
    private final String relativePath;

    /** The name of the class. */
    private String className;

    /** Whether this is an external class. */
    private final boolean isExternalClass;

    /** The class modifiers. */
    private int classModifiers;

    /** Whether this class is an interface. */
    private boolean isInterface;

    /** Whether this class is an annotation. */
    private boolean isAnnotation;

    /** The superclass name. (can be null if no superclass, or if superclass is blacklisted.) */
    private String superclassName;

    /** The implemented interfaces. */
    private List<String> implementedInterfaces;

    /** The class annotations. */
    private AnnotationInfoList classAnnotations;

    /** The fully qualified name of the defining method. */
    private String fullyQualifiedDefiningMethodName;

    /** Class containment entries. */
    private List<SimpleEntry<String, String>> classContainmentEntries;

    /** Annotation default parameter values. */
    private AnnotationParameterValueList annotationParamDefaultValues;

    /** Referenced class names. */
    private Set<String> refdClassNames;

    /** The classfile resource. */
    private final Resource classfileResource;

    /** The field info list. */
    private FieldInfoList fieldInfoList;

    /** The method info list. */
    private MethodInfoList methodInfoList;

    /** The type signature. */
    private String typeSignature;

    /**
     * Class names already scheduled for scanning. If a class name is not in this list, the class is external, and
     * has not yet been scheduled for scanning.
     */
    private final Set<String> classNamesScheduledForScanning;

    /** Any additional work units scheduled for scanning. */
    private List<ClassfileScanWorkUnit> additionalWorkUnits;

    /** The scan spec. */
    private final ScanSpec scanSpec;

    /** The log. */
    private final LogNode log;

    // -------------------------------------------------------------------------------------------------------------

    /** The byte offset for the beginning of each entry in the constant pool. */
    private int[] entryOffset;

    /** The tag (type) for each entry in the constant pool. */
    private int[] entryTag;

    /** The indirection index for String/Class entries in the constant pool. */
    private int[] indirectStringRefs;

    // -------------------------------------------------------------------------------------------------------------

    /** Thrown when a classfile's contents are not in the correct format. */
    class ClassfileFormatException extends IOException {
        /** serialVersionUID. */
        static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message
         *            the message
         */
        public ClassfileFormatException(final String message) {
            super(message);
        }

        /**
         * Constructor.
         *
         * @param message
         *            the message
         * @param cause
         *            the cause
         */
        public ClassfileFormatException(final String message, final Throwable cause) {
            super(message, cause);
        }

        /**
         * Speed up exception (stack trace is not needed for this exception).
         *
         * @return this
         */
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /** Thrown when a classfile needs to be skipped. */
    class SkipClassException extends IOException {
        /** serialVersionUID. */
        static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message
         *            the message
         */
        public SkipClassException(final String message) {
            super(message);
        }

        /**
         * Speed up exception (stack trace is not needed for this exception).
         *
         * @return this
         */
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Extend scanning to a superclass, interface or annotation.
     *
     * @param className
     *            the class name
     * @param relationship
     *            the relationship type
     */
    private void scheduleScanningIfExternalClass(final String className, final String relationship) {
        // The call to classNamesScheduledForScanning.add(className) will return true only for external classes
        // that have not yet been scheduled for scanning
        if (className != null && classNamesScheduledForScanning.add(className)) {
            // Search for the named class' classfile among classpath elements, in classpath order (this is O(N)
            // for each class, but there shouldn't be too many cases of extending scanning upwards)
            final String classfilePath = JarUtils.classNameToClassfilePath(className);
            // First check current classpath element, to avoid iterating through other classpath elements
            Resource classResource = classpathElement.getResource(classfilePath);
            ClasspathElement foundInClasspathElt = null;
            if (classResource != null) {
                // Found the classfile in the current classpath element
                foundInClasspathElt = classpathElement;
            } else {
                // Didn't find the classfile in the current classpath element -- iterate through other elements
                for (final ClasspathElement classpathElt : classpathOrder) {
                    if (classpathElt != classpathElement) {
                        classResource = classpathElt.getResource(classfilePath);
                        if (classResource != null) {
                            foundInClasspathElt = classpathElt;
                            break;
                        }
                    }
                }
            }
            if (classResource != null) {
                // Found class resource 
                if (log != null) {
                    log.log("Scheduling external class for scanning: " + relationship + " " + className
                            + (foundInClasspathElt == classpathElement ? ""
                                    : " -- found in classpath element " + foundInClasspathElt));
                }
                if (additionalWorkUnits == null) {
                    additionalWorkUnits = new ArrayList<>();
                }
                // Schedule class resource for scanning
                additionalWorkUnits.add(new ClassfileScanWorkUnit(foundInClasspathElt, classResource,
                        /* isExternalClass = */ true));
            } else {
                if (log != null && !className.equals("java.lang.Object")) {
                    log.log("External " + relationship + " " + className + " was not found in "
                            + "non-blacklisted packages -- cannot extend scanning to this class");
                }
            }
        }
    }

    /**
     * Check if scanning needs to be extended upwards to an external superclass, interface or annotation.
     */
    private void extendScanningUpwards() {
        // Check superclass
        if (superclassName != null) {
            scheduleScanningIfExternalClass(superclassName, "superclass");
        }
        // Check implemented interfaces
        if (implementedInterfaces != null) {
            for (final String interfaceName : implementedInterfaces) {
                scheduleScanningIfExternalClass(interfaceName, "interface");
            }
        }
        // Check class annotations
        if (classAnnotations != null) {
            for (final AnnotationInfo annotationInfo : classAnnotations) {
                scheduleScanningIfExternalClass(annotationInfo.getName(), "class annotation");
            }
        }
        // Check method annotations and method parameter annotations
        if (methodInfoList != null) {
            for (final MethodInfo methodInfo : methodInfoList) {
                if (methodInfo.annotationInfo != null) {
                    for (final AnnotationInfo methodAnnotationInfo : methodInfo.annotationInfo) {
                        scheduleScanningIfExternalClass(methodAnnotationInfo.getName(), "method annotation");
                    }
                    if (methodInfo.parameterAnnotationInfo != null
                            && methodInfo.parameterAnnotationInfo.length > 0) {
                        for (final AnnotationInfo[] paramAnns : methodInfo.parameterAnnotationInfo) {
                            if (paramAnns != null && paramAnns.length > 0) {
                                for (final AnnotationInfo paramAnn : paramAnns) {
                                    scheduleScanningIfExternalClass(paramAnn.getName(),
                                            "method parameter annotation");
                                }
                            }
                        }
                    }
                }
            }
        }
        // Check field annotations
        if (fieldInfoList != null) {
            for (final FieldInfo fieldInfo : fieldInfoList) {
                if (fieldInfo.annotationInfo != null) {
                    for (final AnnotationInfo fieldAnnotationInfo : fieldInfo.annotationInfo) {
                        scheduleScanningIfExternalClass(fieldAnnotationInfo.getName(), "field annotation");
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Link classes. Not threadsafe, should be run in a single-threaded context.
     *
     * @param classNameToClassInfo
     *            map from class name to class info
     * @param packageNameToPackageInfo
     *            map from package name to package info
     * @param moduleNameToModuleInfo
     *            map from module name to module info
     * @param log
     *            the log
     */
    void link(final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo, final LogNode log) {
        if (className.equals("module-info")) {
            // Handle module descriptor classfile
            String moduleName = null;
            final ModuleRef moduleRef = classfileResource.getModuleRef();
            if (moduleRef != null) {
                // Get module name from ModuleReference of this ClasspathElementModule, if available
                moduleName = moduleRef.getName();
            }
            if (moduleName == null) {
                moduleName = classpathElement.moduleName;
            }
            if (moduleName != null && !moduleName.isEmpty()) {
                // Get or create a ModuleInfo object for this module
                ModuleInfo moduleInfo = moduleNameToModuleInfo.get(moduleName);
                if (moduleInfo == null) {
                    moduleNameToModuleInfo.put(moduleName,
                            moduleInfo = new ModuleInfo(moduleRef, classpathElement));
                }
                // Add any class annotations on the module-info.class file to the ModuleInfo
                moduleInfo.addAnnotations(classAnnotations);
            }

        } else if (className.equals("package-info") || className.endsWith(".package-info")) {
            // Handle package descriptor classfile
            final PackageInfo packageInfo = PackageInfo
                    .getOrCreatePackage(PackageInfo.getParentPackageName(className), packageNameToPackageInfo);
            packageInfo.addAnnotations(classAnnotations);

        } else {
            // Handle regular classfile
            final ClassInfo classInfo = ClassInfo.addScannedClass(className, classModifiers, isExternalClass,
                    classNameToClassInfo, classpathElement, classfileResource);
            classInfo.setModifiers(classModifiers);
            classInfo.setIsInterface(isInterface);
            classInfo.setIsAnnotation(isAnnotation);
            if (superclassName != null) {
                classInfo.addSuperclass(superclassName, classNameToClassInfo);
            }
            if (implementedInterfaces != null) {
                for (final String interfaceName : implementedInterfaces) {
                    classInfo.addImplementedInterface(interfaceName, classNameToClassInfo);
                }
            }
            if (classAnnotations != null) {
                for (final AnnotationInfo classAnnotation : classAnnotations) {
                    classInfo.addClassAnnotation(classAnnotation, classNameToClassInfo);
                }
            }
            if (classContainmentEntries != null) {
                ClassInfo.addClassContainment(classContainmentEntries, classNameToClassInfo);
            }
            if (annotationParamDefaultValues != null) {
                classInfo.addAnnotationParamDefaultValues(annotationParamDefaultValues);
            }
            if (fullyQualifiedDefiningMethodName != null) {
                classInfo.addFullyQualifiedDefiningMethodName(fullyQualifiedDefiningMethodName);
            }
            if (fieldInfoList != null) {
                classInfo.addFieldInfo(fieldInfoList, classNameToClassInfo);
            }
            if (methodInfoList != null) {
                classInfo.addMethodInfo(methodInfoList, classNameToClassInfo);
            }
            if (typeSignature != null) {
                classInfo.setTypeSignature(typeSignature);
            }
            if (refdClassNames != null) {
                classInfo.addReferencedClassNames(refdClassNames);
            }

            final PackageInfo packageInfo = PackageInfo
                    .getOrCreatePackage(PackageInfo.getParentPackageName(className), packageNameToPackageInfo);
            packageInfo.addClassInfo(classInfo);

            String moduleName = null;
            final ModuleRef moduleRef = classInfo.getModuleRef();
            if (moduleRef != null) {
                // Get module name from ModuleReference of this ClasspathElementModule, if available
                moduleName = moduleRef.getName();
            }
            if (moduleName == null) {
                // Otherwise get the module name from any module-info.class file found in the classpath element
                moduleName = classpathElement.moduleName;
            }
            // Only add class to ModuleInfo if a module name is defined, and if it's not empty
            if (moduleName != null && !moduleName.isEmpty()) {
                ModuleInfo moduleInfo = moduleNameToModuleInfo.get(moduleName);
                if (moduleInfo == null) {
                    moduleNameToModuleInfo.put(moduleName,
                            moduleInfo = new ModuleInfo(moduleRef, classpathElement));
                }
                moduleInfo.addClassInfo(classInfo);
                moduleInfo.addPackageInfo(packageInfo);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the byte offset within the buffer of a string from the constant pool, or 0 for a null string.
     *
     * @param cpIdx
     *            the constant pool index
     * @param subFieldIdx
     *            should be 0 for CONSTANT_Utf8, CONSTANT_Class and CONSTANT_String, and for
     *            CONSTANT_NameAndType_info, fetches the name for value 0, or the type descriptor for value 1.
     * @return the constant pool string offset
     * @throws ClassfileFormatException
     *             If a problem is detected
     */
    private int getConstantPoolStringOffset(final int cpIdx, final int subFieldIdx)
            throws ClassfileFormatException {
        final int t = entryTag[cpIdx];
        if ((t != 12 && subFieldIdx != 0) || (t == 12 && subFieldIdx != 0 && subFieldIdx != 1)) {
            throw new ClassfileFormatException(
                    "Bad subfield index " + subFieldIdx + " for tag " + t + ", cannot continue reading class. "
                            + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        int cpIdxToUse;
        if (t == 0) {
            // Assume this means null
            return 0;
        } else if (t == 1) {
            // CONSTANT_Utf8
            cpIdxToUse = cpIdx;
        } else if (t == 7 || t == 8 || t == 19) {
            // t == 7 => CONSTANT_Class, e.g. "[[I", "[Ljava/lang/Thread;"; t == 8 => CONSTANT_String;
            // t == 19 => CONSTANT_Method_Info
            final int indirIdx = indirectStringRefs[cpIdx];
            if (indirIdx == -1) {
                // Should not happen
                throw new ClassfileFormatException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            if (indirIdx == 0) {
                // I assume this represents a null string, since the zeroeth entry is unused
                return 0;
            }
            cpIdxToUse = indirIdx;
        } else if (t == 12) {
            // CONSTANT_NameAndType_info
            final int compoundIndirIdx = indirectStringRefs[cpIdx];
            if (compoundIndirIdx == -1) {
                // Should not happen
                throw new ClassfileFormatException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            final int indirIdx = (subFieldIdx == 0 ? (compoundIndirIdx >> 16) : compoundIndirIdx) & 0xffff;
            if (indirIdx == 0) {
                // Should not happen
                throw new ClassfileFormatException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            cpIdxToUse = indirIdx;
        } else {
            throw new ClassfileFormatException("Wrong tag number " + t + " at constant pool index " + cpIdx + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        }
        return entryOffset[cpIdxToUse];
    }

    /**
     * Get a string from the constant pool, optionally replacing '/' with '.'.
     *
     * @param cpIdx
     *            the constant pool index
     * @param replaceSlashWithDot
     *            if true, replace slash with dot in the result.
     * @param stripLSemicolon
     *            if true, strip 'L' from the beginning and ';' from the end before returning (for class reference
     *            constants)
     * @return the constant pool string
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolString(final int cpIdx, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws ClassfileFormatException, IOException {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        return constantPoolStringOffset == 0 ? null
                : inputStreamOrByteBuffer.readString(constantPoolStringOffset, replaceSlashWithDot,
                        stripLSemicolon);
    }

    /**
     * Get a string from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index
     * @param subFieldIdx
     *            should be 0 for CONSTANT_Utf8, CONSTANT_Class and CONSTANT_String, and for
     *            CONSTANT_NameAndType_info, fetches the name for value 0, or the type descriptor for value 1.
     * @return the constant pool string
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolString(final int cpIdx, final int subFieldIdx)
            throws ClassfileFormatException, IOException {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, subFieldIdx);
        return constantPoolStringOffset == 0 ? null
                : inputStreamOrByteBuffer.readString(constantPoolStringOffset, /* replaceSlashWithDot = */ false,
                        /* stripLSemicolon = */ false);
    }

    /**
     * Get a string from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index
     * @return the constant pool string
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolString(final int cpIdx) throws ClassfileFormatException, IOException {
        return getConstantPoolString(cpIdx, /* subFieldIdx = */ 0);
    }

    /**
     * Get the first UTF8 byte of a string in the constant pool, or '\0' if the string is null or empty.
     *
     * @param cpIdx
     *            the constant pool index
     * @return the first byte of the constant pool string
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private byte getConstantPoolStringFirstByte(final int cpIdx) throws ClassfileFormatException, IOException {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (constantPoolStringOffset == 0) {
            return '\0';
        }
        final int utfLen = inputStreamOrByteBuffer.readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return '\0';
        }
        return inputStreamOrByteBuffer.buf[constantPoolStringOffset + 2];
    }

    /**
     * Get a string from the constant pool, and interpret it as a class name by replacing '/' with '.'.
     *
     * @param CpIdx
     *            the constant pool index
     * @return the constant pool class name
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolClassName(final int CpIdx) throws ClassfileFormatException, IOException {
        return getConstantPoolString(CpIdx, /* replaceSlashWithDot = */ true, /* stripLSemicolon = */ false);
    }

    /**
     * Get a string from the constant pool representing an internal string descriptor for a class name
     * ("Lcom/xyz/MyClass;"), and interpret it as a class name by replacing '/' with '.', and removing the leading
     * "L" and the trailing ";".
     *
     * @param CpIdx
     *            the constant pool index
     * @return the constant pool class descriptor
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolClassDescriptor(final int CpIdx) throws ClassfileFormatException, IOException {
        return getConstantPoolString(CpIdx, /* replaceSlashWithDot = */ true, /* stripLSemicolon = */ true);
    }

    /**
     * Compare a string in the constant pool with a given constant, without constructing the String object.
     *
     * @param cpIdx
     *            the constant pool index
     * @param otherString
     *            the other string
     * @return true, if successful
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private boolean constantPoolStringEquals(final int cpIdx, final String otherString)
            throws ClassfileFormatException, IOException {
        final int strOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (strOffset == 0) {
            return otherString == null;
        } else if (otherString == null) {
            return false;
        }
        final int strLen = inputStreamOrByteBuffer.readUnsignedShort(strOffset);
        final int otherLen = otherString.length();
        if (strLen != otherLen) {
            return false;
        }
        final int strStart = strOffset + 2;
        for (int i = 0; i < strLen; i++) {
            if ((char) (inputStreamOrByteBuffer.buf[strStart + i] & 0xff) != otherString.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get a field constant from the constant pool.
     *
     * @param tag
     *            the tag
     * @param fieldTypeDescriptorFirstChar
     *            the first char of the field type descriptor
     * @param cpIdx
     *            the constant pool index
     * @return the field constant pool value
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private Object getFieldConstantPoolValue(final int tag, final char fieldTypeDescriptorFirstChar,
            final int cpIdx) throws ClassfileFormatException, IOException {
        switch (tag) {
        case 1: // Modified UTF8
        case 7: // Class -- N.B. Unused? Class references do not seem to actually be stored as constant initalizers
        case 8: // String
            // Forward or backward indirect reference to a modified UTF8 entry
            return getConstantPoolString(cpIdx);
        case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
            final int intVal = inputStreamOrByteBuffer.readInt(entryOffset[cpIdx]);
            switch (fieldTypeDescriptorFirstChar) {
            case 'I':
                return intVal;
            case 'S':
                return (short) intVal;
            case 'C':
                return (char) intVal;
            case 'B':
                return (byte) intVal;
            case 'Z':
                return intVal != 0;
            default:
                // Fall through
            }
            throw new ClassfileFormatException("Unknown Constant_INTEGER type " + fieldTypeDescriptorFirstChar
                    + ", " + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        case 4: // float
            return Float.intBitsToFloat(inputStreamOrByteBuffer.readInt(entryOffset[cpIdx]));
        case 5: // long
            return inputStreamOrByteBuffer.readLong(entryOffset[cpIdx]);
        case 6: // double
            return Double.longBitsToDouble(inputStreamOrByteBuffer.readLong(entryOffset[cpIdx]));
        default:
            // ClassGraph doesn't expect other types
            // (N.B. in particular, enum values are not stored in the constant pool, so don't need to be handled)  
            throw new ClassfileFormatException("Unknown constant pool tag " + tag + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read annotation entry from classfile.
     *
     * @return the annotation, as an {@link AnnotationInfo} object.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private AnnotationInfo readAnnotation() throws IOException {
        // Lcom/xyz/Annotation; -> Lcom.xyz.Annotation;
        final String annotationClassName = getConstantPoolClassDescriptor(
                inputStreamOrByteBuffer.readUnsignedShort());
        final int numElementValuePairs = inputStreamOrByteBuffer.readUnsignedShort();
        AnnotationParameterValueList paramVals = null;
        if (numElementValuePairs > 0) {
            paramVals = new AnnotationParameterValueList(numElementValuePairs);
            for (int i = 0; i < numElementValuePairs; i++) {
                final String paramName = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
                final Object paramValue = readAnnotationElementValue();
                paramVals.add(new AnnotationParameterValue(paramName, paramValue));
            }
        }
        return new AnnotationInfo(annotationClassName, paramVals);
    }

    /**
     * Read annotation element value from classfile.
     *
     * @return the annotation element value
     * @throws IOException
     *             If an IO exception occurs.
     */
    private Object readAnnotationElementValue() throws IOException {
        final int tag = (char) inputStreamOrByteBuffer.readUnsignedByte();
        switch (tag) {
        case 'B':
            return (byte) inputStreamOrByteBuffer.readInt(entryOffset[inputStreamOrByteBuffer.readUnsignedShort()]);
        case 'C':
            return (char) inputStreamOrByteBuffer.readInt(entryOffset[inputStreamOrByteBuffer.readUnsignedShort()]);
        case 'D':
            return Double.longBitsToDouble(
                    inputStreamOrByteBuffer.readLong(entryOffset[inputStreamOrByteBuffer.readUnsignedShort()]));
        case 'F':
            return Float.intBitsToFloat(
                    inputStreamOrByteBuffer.readInt(entryOffset[inputStreamOrByteBuffer.readUnsignedShort()]));
        case 'I':
            return inputStreamOrByteBuffer.readInt(entryOffset[inputStreamOrByteBuffer.readUnsignedShort()]);
        case 'J':
            return inputStreamOrByteBuffer.readLong(entryOffset[inputStreamOrByteBuffer.readUnsignedShort()]);
        case 'S':
            return (short) inputStreamOrByteBuffer
                    .readInt(entryOffset[inputStreamOrByteBuffer.readUnsignedShort()]);
        case 'Z':
            return inputStreamOrByteBuffer.readInt(entryOffset[inputStreamOrByteBuffer.readUnsignedShort()]) != 0;
        case 's':
            return getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
        case 'e': {
            // Return type is AnnotationEnumVal.
            final String annotationClassName = getConstantPoolClassDescriptor(
                    inputStreamOrByteBuffer.readUnsignedShort());
            final String annotationConstName = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
            return new AnnotationEnumValue(annotationClassName, annotationConstName);
        }
        case 'c':
            // Return type is AnnotationClassRef (for class references in annotations)
            final String classRefTypeDescriptor = getConstantPoolString(
                    inputStreamOrByteBuffer.readUnsignedShort());
            return new AnnotationClassRef(classRefTypeDescriptor);
        case '@':
            // Complex (nested) annotation. Return type is AnnotationInfo.
            return readAnnotation();
        case '[':
            // Return type is Object[] (of nested annotation element values)
            final int count = inputStreamOrByteBuffer.readUnsignedShort();
            final Object[] arr = new Object[count];
            for (int i = 0; i < count; ++i) {
                // Nested annotation element value
                arr[i] = readAnnotationElementValue();
            }
            return arr;
        default:
            throw new ClassfileFormatException("Class " + className + " has unknown annotation element type tag '"
                    + ((char) tag) + "': element size unknown, cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
    }

    /** An empty array for the case where there are no annotations. */
    private static final AnnotationInfo[] NO_ANNOTATIONS = new AnnotationInfo[0];

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read constant pool entries.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void readConstantPoolEntries() throws IOException {
        // Only record class dependency info if inter-class dependencies are enabled
        List<Integer> classNameCpIdxs = null;
        List<Integer> typeSignatureIdxs = null;
        if (scanSpec.enableInterClassDependencies) {
            classNameCpIdxs = new ArrayList<Integer>();
            typeSignatureIdxs = new ArrayList<Integer>();
        }

        // Read size of constant pool
        final int cpCount = inputStreamOrByteBuffer.readUnsignedShort();

        // Allocate storage for constant pool, or reuse storage if there's enough left from the previous scan
        if (entryOffset == null || entryOffset.length < cpCount) {
            entryOffset = new int[cpCount];
            entryTag = new int[cpCount];
            indirectStringRefs = new int[cpCount];
        }
        Arrays.fill(indirectStringRefs, 0, cpCount, -1);

        // Read constant pool entries
        for (int i = 1, skipSlot = 0; i < cpCount; i++) {
            if (skipSlot == 1) {
                // Skip a slot (keeps Scrutinizer happy -- it doesn't like i++ in case 6)
                skipSlot = 0;
                continue;
            }
            entryTag[i] = inputStreamOrByteBuffer.readUnsignedByte();
            entryOffset[i] = inputStreamOrByteBuffer.curr;
            switch (entryTag[i]) {
            case 0: // Impossible, probably buffer underflow
                throw new ClassfileFormatException("Unknown constant pool tag 0 in classfile " + relativePath
                        + " (possible buffer underflow issue). Please report this at "
                        + "https://github.com/classgraph/classgraph/issues");
            case 1: // Modified UTF8
                final int strLen = inputStreamOrByteBuffer.readUnsignedShort();
                inputStreamOrByteBuffer.skip(strLen);
                break;
            case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
            case 4: // float
                inputStreamOrByteBuffer.skip(4);
                break;
            case 5: // long
            case 6: // double
                inputStreamOrByteBuffer.skip(8);
                skipSlot = 1; // double slot
                break;
            case 7: // Class reference (format is e.g. "java/lang/String")
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRefs[i] = inputStreamOrByteBuffer.readUnsignedShort();
                if (scanSpec.enableInterClassDependencies && entryTag[i] == 7) {
                    // If this is a class ref, and inter-class dependencies are enabled, record the dependency
                    classNameCpIdxs.add(indirectStringRefs[i]);
                }
                break;
            case 8: // String
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRefs[i] = inputStreamOrByteBuffer.readUnsignedShort();
                break;
            case 9: // field ref
                // Refers to a class ref (case 7) and then a name and type (case 12)
                inputStreamOrByteBuffer.skip(4);
                break;
            case 10: // method ref
                // Refers to a class ref (case 7) and then a name and type (case 12)
                inputStreamOrByteBuffer.skip(4);
                break;
            case 11: // interface method ref
                // Refers to a class ref (case 7) and then a name and type (case 12)
                inputStreamOrByteBuffer.skip(4);
                break;
            case 12: // name and type
                final int nameRef = inputStreamOrByteBuffer.readUnsignedShort();
                final int typeRef = inputStreamOrByteBuffer.readUnsignedShort();
                if (scanSpec.enableInterClassDependencies) {
                    typeSignatureIdxs.add(typeRef);
                }
                indirectStringRefs[i] = (nameRef << 16) | typeRef;
                break;
            case 15: // method handle
                inputStreamOrByteBuffer.skip(3);
                break;
            case 16: // method type
                inputStreamOrByteBuffer.skip(2);
                break;
            case 18: // invoke dynamic
                inputStreamOrByteBuffer.skip(4);
                break;
            case 19: // module (for module-info.class in JDK9+)
                // see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
                indirectStringRefs[i] = inputStreamOrByteBuffer.readUnsignedShort();
                break;
            case 20: // package (for module-info.class in JDK9+)
                // see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
                inputStreamOrByteBuffer.skip(2);
                break;
            default:
                throw new ClassfileFormatException("Unknown constant pool tag " + entryTag[i]
                        + " (element size unknown, cannot continue reading class). Please report this at "
                        + "https://github.com/classgraph/classgraph/issues");
            }
        }

        // Find classes referenced in the constant pool (note that there are some class refs that will not be
        // found this way, e.g. enum classes and class refs in annotation parameter values, since they are
        // referenced as strings (tag 1) rather than classes (tag 7) or type signatures (part of tag 12)).
        if (scanSpec.enableInterClassDependencies) {
            refdClassNames = new HashSet<>();
            // Get class names from direct class references in constant pool
            for (final int cpIdx : classNameCpIdxs) {
                final String refdClassName = getConstantPoolString(cpIdx, /* replaceSlashWithDot = */ true,
                        /* stripLSemicolon = */ false);
                if (refdClassName != null) {
                    if (refdClassName.startsWith("[")) {
                        // Parse array type signature, e.g. "[Ljava.lang.String;" -- uses '.' rather than '/'
                        try {
                            final TypeSignature typeSig = TypeSignature.parse(refdClassName.replace('.', '/'),
                                    /* definingClass = */ null);
                            typeSig.findReferencedClassNames(refdClassNames);
                        } catch (final ParseException e) {
                            // Should not happen
                            throw new ClassfileFormatException("Could not parse class name: " + refdClassName, e);
                        }
                    } else {
                        refdClassNames.add(refdClassName);
                    }
                }
            }
            // Get class names from type signatures in "name and type" entries in constant pool
            for (final int cpIdx : typeSignatureIdxs) {
                final String typeSigStr = getConstantPoolString(cpIdx);
                if (typeSigStr != null) {
                    try {
                        if (typeSigStr.indexOf('(') >= 0 || typeSigStr.equals("<init>")) {
                            // Parse the type signature
                            final MethodTypeSignature typeSig = MethodTypeSignature.parse(typeSigStr,
                                    /* definingClassName = */ null);
                            // Extract class names from type signature
                            typeSig.findReferencedClassNames(refdClassNames);
                        } else {
                            // Parse the type signature
                            final TypeSignature typeSig = TypeSignature.parse(typeSigStr,
                                    /* definingClassName = */ null);
                            // Extract class names from type signature
                            typeSig.findReferencedClassNames(refdClassNames);
                        }
                    } catch (final ParseException e) {
                        throw new ClassfileFormatException("Could not parse type signature: " + typeSigStr, e);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read basic class information.
     * 
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     * @throws SkipClassException
     *             if the classfile needs to be skipped (e.g. the class is non-public, and ignoreClassVisibility is
     *             false)
     */
    private void readBasicClassInfo() throws IOException, ClassfileFormatException, SkipClassException {
        // Modifier flags
        classModifiers = inputStreamOrByteBuffer.readUnsignedShort();
        isInterface = (classModifiers & 0x0200) != 0;
        isAnnotation = (classModifiers & 0x2000) != 0;
        final boolean isModule = (classModifiers & 0x8000) != 0; // Equivalently filename is "module-info.class"
        final boolean isPackage = relativePath.regionMatches(relativePath.lastIndexOf('/') + 1,
                "package-info.class", 0, 18);

        // The fully-qualified class name of this class, with slashes replaced with dots
        final String classNamePath = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
        if (classNamePath == null) {
            throw new ClassfileFormatException("Class name is null");
        }
        className = classNamePath.replace('/', '.');
        if ("java.lang.Object".equals(className)) {
            // Don't process java.lang.Object (it has a null superclass), though you can still search for classes
            // that are subclasses of java.lang.Object (as an external class).
            throw new SkipClassException("No need to scan java.lang.Object");
        }

        // Check class visibility modifiers
        if (!scanSpec.ignoreClassVisibility && !Modifier.isPublic(classModifiers) && !isModule && !isPackage) {
            throw new SkipClassException("Class is not public, and ignoreClassVisibility() was not called");
        }

        // Make sure classname matches relative path
        if (!relativePath.endsWith(".class")) {
            // Should not happen
            throw new SkipClassException("Classfile filename " + relativePath + " does not end in \".class\"");
        }
        final int len = classNamePath.length();
        if (relativePath.length() != len + 6 || !classNamePath.regionMatches(0, relativePath, 0, len)) {
            throw new SkipClassException(
                    "Relative path " + relativePath + " does not match class name " + className);
        }

        // Superclass name, with slashes replaced with dots
        final int superclassNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
        if (superclassNameCpIdx > 0) {
            superclassName = getConstantPoolClassName(superclassNameCpIdx);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the class' interfaces.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     */
    private void readInterfaces() throws IOException {
        // Interfaces
        final int interfaceCount = inputStreamOrByteBuffer.readUnsignedShort();
        for (int i = 0; i < interfaceCount; i++) {
            final String interfaceName = getConstantPoolClassName(inputStreamOrByteBuffer.readUnsignedShort());
            if (implementedInterfaces == null) {
                implementedInterfaces = new ArrayList<>();
            }
            implementedInterfaces.add(interfaceName);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the class' fields.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     */
    private void readFields() throws IOException, ClassfileFormatException {
        // Fields
        final int fieldCount = inputStreamOrByteBuffer.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.5
            final int fieldModifierFlags = inputStreamOrByteBuffer.readUnsignedShort();
            final boolean isPublicField = ((fieldModifierFlags & 0x0001) == 0x0001);
            final boolean isStaticFinalField = ((fieldModifierFlags & 0x0018) == 0x0018);
            final boolean fieldIsVisible = isPublicField || scanSpec.ignoreFieldVisibility;
            final boolean getStaticFinalFieldConstValue = scanSpec.enableStaticFinalFieldConstantInitializerValues
                    && isStaticFinalField && fieldIsVisible;
            if (!fieldIsVisible || (!scanSpec.enableFieldInfo && !getStaticFinalFieldConstValue)) {
                // Skip field
                inputStreamOrByteBuffer.readUnsignedShort(); // fieldNameCpIdx
                inputStreamOrByteBuffer.readUnsignedShort(); // fieldTypeDescriptorCpIdx
                final int attributesCount = inputStreamOrByteBuffer.readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    inputStreamOrByteBuffer.readUnsignedShort(); // attributeNameCpIdx
                    final int attributeLength = inputStreamOrByteBuffer.readInt(); // == 2
                    inputStreamOrByteBuffer.skip(attributeLength);
                }
            } else {
                final int fieldNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                final String fieldName = getConstantPoolString(fieldNameCpIdx);
                final int fieldTypeDescriptorCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                final char fieldTypeDescriptorFirstChar = (char) getConstantPoolStringFirstByte(
                        fieldTypeDescriptorCpIdx);
                String fieldTypeDescriptor;
                String fieldTypeSignature = null;
                fieldTypeDescriptor = getConstantPoolString(fieldTypeDescriptorCpIdx);

                Object fieldConstValue = null;
                AnnotationInfoList fieldAnnotationInfo = null;
                final int attributesCount = inputStreamOrByteBuffer.readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                    final int attributeLength = inputStreamOrByteBuffer.readInt(); // == 2
                    // See if field name matches one of the requested names for this class, and if it does,
                    // check if it is initialized with a constant value
                    if ((getStaticFinalFieldConstValue)
                            && constantPoolStringEquals(attributeNameCpIdx, "ConstantValue")) {
                        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                        final int cpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                        fieldConstValue = getFieldConstantPoolValue(entryTag[cpIdx], fieldTypeDescriptorFirstChar,
                                cpIdx);
                    } else if (fieldIsVisible && constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        fieldTypeSignature = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
                    } else if (scanSpec.enableAnnotationInfo //
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                        // Read annotation names
                        final int fieldAnnotationCount = inputStreamOrByteBuffer.readUnsignedShort();
                        if (fieldAnnotationInfo == null && fieldAnnotationCount > 0) {
                            fieldAnnotationInfo = new AnnotationInfoList(1);
                        }
                        if (fieldAnnotationInfo != null) {
                            for (int k = 0; k < fieldAnnotationCount; k++) {
                                final AnnotationInfo fieldAnnotation = readAnnotation();
                                fieldAnnotationInfo.add(fieldAnnotation);
                            }
                        }
                    } else {
                        // No match, just skip attribute
                        inputStreamOrByteBuffer.skip(attributeLength);
                    }
                }
                if (scanSpec.enableFieldInfo && fieldIsVisible) {
                    if (fieldInfoList == null) {
                        fieldInfoList = new FieldInfoList();
                    }
                    fieldInfoList.add(new FieldInfo(className, fieldName, fieldModifierFlags, fieldTypeDescriptor,
                            fieldTypeSignature, fieldConstValue, fieldAnnotationInfo));
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the class' methods.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     */
    private void readMethods() throws IOException, ClassfileFormatException {
        // Methods
        final int methodCount = inputStreamOrByteBuffer.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
            final int methodModifierFlags = inputStreamOrByteBuffer.readUnsignedShort();
            final boolean isPublicMethod = ((methodModifierFlags & 0x0001) == 0x0001);
            final boolean methodIsVisible = isPublicMethod || scanSpec.ignoreMethodVisibility;

            String methodName = null;
            String methodTypeDescriptor = null;
            String methodTypeSignature = null;
            // Always enable MethodInfo for annotations (this is how annotation constants are defined)
            final boolean enableMethodInfo = scanSpec.enableMethodInfo || isAnnotation;
            if (enableMethodInfo || isAnnotation) { // Annotations store defaults in method_info
                final int methodNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                methodName = getConstantPoolString(methodNameCpIdx);
                final int methodTypeDescriptorCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                methodTypeDescriptor = getConstantPoolString(methodTypeDescriptorCpIdx);
            } else {
                inputStreamOrByteBuffer.skip(4); // name_index, descriptor_index
            }
            final int attributesCount = inputStreamOrByteBuffer.readUnsignedShort();
            String[] methodParameterNames = null;
            int[] methodParameterModifiers = null;
            AnnotationInfo[][] methodParameterAnnotations = null;
            AnnotationInfoList methodAnnotationInfo = null;
            boolean methodHasBody = false;
            if (!methodIsVisible || (!enableMethodInfo && !isAnnotation)) {
                // Skip method attributes
                for (int j = 0; j < attributesCount; j++) {
                    inputStreamOrByteBuffer.skip(2); // attribute_name_index
                    final int attributeLength = inputStreamOrByteBuffer.readInt();
                    inputStreamOrByteBuffer.skip(attributeLength);
                }
            } else {
                // Look for method annotations
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                    final int attributeLength = inputStreamOrByteBuffer.readInt();
                    if (scanSpec.enableAnnotationInfo
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                        final int methodAnnotationCount = inputStreamOrByteBuffer.readUnsignedShort();
                        if (methodAnnotationInfo == null && methodAnnotationCount > 0) {
                            methodAnnotationInfo = new AnnotationInfoList(1);
                        }
                        if (methodAnnotationInfo != null) {
                            for (int k = 0; k < methodAnnotationCount; k++) {
                                final AnnotationInfo annotationInfo = readAnnotation();
                                methodAnnotationInfo.add(annotationInfo);
                            }
                        }
                    } else if (scanSpec.enableAnnotationInfo
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleParameterAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleParameterAnnotations")))) {
                        final int paramCount = inputStreamOrByteBuffer.readUnsignedByte();
                        methodParameterAnnotations = new AnnotationInfo[paramCount][];
                        for (int k = 0; k < paramCount; k++) {
                            final int numAnnotations = inputStreamOrByteBuffer.readUnsignedShort();
                            methodParameterAnnotations[k] = numAnnotations == 0 ? NO_ANNOTATIONS
                                    : new AnnotationInfo[numAnnotations];
                            for (int l = 0; l < numAnnotations; l++) {
                                methodParameterAnnotations[k][l] = readAnnotation();
                            }
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "MethodParameters")) {
                        // Read method parameters. For Java, these are only produced in JDK8+, and only if the
                        // commandline switch `-parameters` is provided at compiletime.
                        final int paramCount = inputStreamOrByteBuffer.readUnsignedByte();
                        methodParameterNames = new String[paramCount];
                        methodParameterModifiers = new int[paramCount];
                        for (int k = 0; k < paramCount; k++) {
                            final int cpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                            // If the constant pool index is zero, then the parameter is unnamed => use null
                            methodParameterNames[k] = cpIdx == 0 ? null : getConstantPoolString(cpIdx);
                            methodParameterModifiers[k] = inputStreamOrByteBuffer.readUnsignedShort();
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        // Add type params to method type signature
                        methodTypeSignature = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "AnnotationDefault")) {
                        if (annotationParamDefaultValues == null) {
                            annotationParamDefaultValues = new AnnotationParameterValueList();
                        }
                        this.annotationParamDefaultValues.add(new AnnotationParameterValue(methodName,
                                // Get annotation parameter default value
                                readAnnotationElementValue()));
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "Code")) {
                        methodHasBody = true;
                        inputStreamOrByteBuffer.skip(attributeLength);
                    } else {
                        inputStreamOrByteBuffer.skip(attributeLength);
                    }
                }
                // Create MethodInfo
                if (enableMethodInfo) {
                    if (methodInfoList == null) {
                        methodInfoList = new MethodInfoList();
                    }
                    methodInfoList.add(new MethodInfo(className, methodName, methodAnnotationInfo,
                            methodModifierFlags, methodTypeDescriptor, methodTypeSignature, methodParameterNames,
                            methodParameterModifiers, methodParameterAnnotations, methodHasBody));
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read class attributes.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     */
    private void readClassAttributes() throws IOException, ClassfileFormatException {
        // Class attributes (including class annotations, class type variables, module info, etc.)
        final int attributesCount = inputStreamOrByteBuffer.readUnsignedShort();
        for (int i = 0; i < attributesCount; i++) {
            final int attributeNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
            final int attributeLength = inputStreamOrByteBuffer.readInt();
            if (scanSpec.enableAnnotationInfo //
                    && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                            || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                    attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                final int annotationCount = inputStreamOrByteBuffer.readUnsignedShort();
                for (int m = 0; m < annotationCount; m++) {
                    if (classAnnotations == null) {
                        classAnnotations = new AnnotationInfoList();
                    }
                    classAnnotations.add(readAnnotation());
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "InnerClasses")) {
                final int numInnerClasses = inputStreamOrByteBuffer.readUnsignedShort();
                for (int j = 0; j < numInnerClasses; j++) {
                    final int innerClassInfoCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                    final int outerClassInfoCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                    if (innerClassInfoCpIdx != 0 && outerClassInfoCpIdx != 0) {
                        if (classContainmentEntries == null) {
                            classContainmentEntries = new ArrayList<>();
                        }
                        classContainmentEntries.add(new SimpleEntry<>(getConstantPoolClassName(innerClassInfoCpIdx),
                                getConstantPoolClassName(outerClassInfoCpIdx)));
                    }
                    inputStreamOrByteBuffer.skip(2); // inner_name_idx
                    inputStreamOrByteBuffer.skip(2); // inner_class_access_flags
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                // Get class type signature, including type variables
                typeSignature = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
            } else if (constantPoolStringEquals(attributeNameCpIdx, "EnclosingMethod")) {
                final String innermostEnclosingClassName = getConstantPoolClassName(
                        inputStreamOrByteBuffer.readUnsignedShort());
                final int enclosingMethodCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                String definingMethodName;
                if (enclosingMethodCpIdx == 0) {
                    // A cpIdx of 0 (which is an invalid value) is used for anonymous inner classes declared in
                    // class initializer code, e.g. assigned to a class field.
                    definingMethodName = "<clinit>";
                } else {
                    definingMethodName = getConstantPoolString(enclosingMethodCpIdx, /* subFieldIdx = */ 0);
                    // Could also fetch method type signature using subFieldIdx = 1, if needed
                }
                // Link anonymous inner classes into the class with their containing method
                if (classContainmentEntries == null) {
                    classContainmentEntries = new ArrayList<>();
                }
                classContainmentEntries.add(new SimpleEntry<>(className, innermostEnclosingClassName));
                // Also store the fully-qualified name of the enclosing method, to mark this as an anonymous inner
                // class
                this.fullyQualifiedDefiningMethodName = innermostEnclosingClassName + "." + definingMethodName;
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Module")) {
                final int moduleNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                String moduleName = getConstantPoolString(moduleNameCpIdx);
                if (moduleName == null) {
                    moduleName = "";
                }
                classpathElement.moduleName = moduleName;
                // (Future work): parse the rest of the module descriptor fields, and add to ModuleInfo:
                // https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.7.25
                inputStreamOrByteBuffer.skip(attributeLength - 2);
            } else {
                inputStreamOrByteBuffer.skip(attributeLength);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Directly examine contents of classfile binary header to determine annotations, implemented interfaces, the
     * super-class etc. Creates a new ClassInfo object, and adds it to classNameToClassInfoOut. Assumes classpath
     * masking has already been performed, so that only one class of a given name will be added.
     *
     * @param classpathElement
     *            the classpath element
     * @param classpathOrder
     *            the classpath order
     * @param classNamesScheduledForScanning
     *            the class names scheduled for scanning
     * @param relativePath
     *            the relative path
     * @param classfileResource
     *            the classfile resource
     * @param isExternalClass
     *            if this is an external class
     * @param workQueue
     *            the work queue
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the log
     * @throws IOException
     *             If an IO exception occurs.
     * @throws ClassfileFormatException
     *             If a problem occurs while parsing the classfile.
     * @throws SkipClassException
     *             if the classfile needs to be skipped (e.g. the class is non-public, and ignoreClassVisibility is
     *             false)
     */
    Classfile(final ClasspathElement classpathElement, final List<ClasspathElement> classpathOrder,
            final Set<String> classNamesScheduledForScanning, final String relativePath,
            final Resource classfileResource, final boolean isExternalClass,
            final WorkQueue<ClassfileScanWorkUnit> workQueue, final ScanSpec scanSpec, final LogNode log)
            throws IOException, ClassfileFormatException, SkipClassException {
        this.classpathElement = classpathElement;
        this.classpathOrder = classpathOrder;
        this.relativePath = relativePath;
        this.classNamesScheduledForScanning = classNamesScheduledForScanning;
        this.classfileResource = classfileResource;
        this.isExternalClass = isExternalClass;
        this.scanSpec = scanSpec;
        this.log = log;

        try {
            // Open classfile as a ByteBuffer or InputStream
            inputStreamOrByteBuffer = classfileResource.openOrRead();

            // Check magic number
            if (inputStreamOrByteBuffer.readInt() != 0xCAFEBABE) {
                throw new ClassfileFormatException("Classfile does not have correct magic number");
            }

            // Read classfile minor version
            inputStreamOrByteBuffer.readUnsignedShort();

            // Read classfile major version
            inputStreamOrByteBuffer.readUnsignedShort();

            // Read the constant pool
            readConstantPoolEntries();

            // Read basic class info (
            readBasicClassInfo();

            // Read interfaces
            readInterfaces();

            // Read fields
            readFields();

            // Read methods
            readMethods();

            // Read class attributes
            readClassAttributes();

        } finally {
            // Close ByteBuffer or InputStream
            classfileResource.close();
            inputStreamOrByteBuffer = null;
        }

        // Check if any superclasses, interfaces or annotations are external (non-whitelisted) classes
        // that need to be scheduled for scanning, so that all of the "upwards" direction of the class
        // graph is scanned for any whitelisted class, even if the superclasses / interfaces / annotations
        // are not themselves whitelisted.
        if (scanSpec.extendScanningUpwardsToExternalClasses) {
            extendScanningUpwards();
            // If any external classes were found, schedule them for scanning
            if (additionalWorkUnits != null) {
                workQueue.addWorkUnits(additionalWorkUnits);
            }
        }

        // Write class info to log 
        if (log != null) {
            final LogNode subLog = log.log("Found " //
                    + (isAnnotation ? "annotation class" : isInterface ? "interface class" : "class") //
                    + " " + className);
            if (superclassName != null) {
                subLog.log(
                        "Super" + (isInterface && !isAnnotation ? "interface" : "class") + ": " + superclassName);
            }
            if (implementedInterfaces != null) {
                subLog.log("Interfaces: " + Join.join(", ", implementedInterfaces));
            }
            if (classAnnotations != null) {
                subLog.log("Class annotations: " + Join.join(", ", classAnnotations));
            }
            if (annotationParamDefaultValues != null) {
                for (final AnnotationParameterValue apv : annotationParamDefaultValues) {
                    subLog.log("Annotation default param value: " + apv);
                }
            }
            if (fieldInfoList != null) {
                for (final FieldInfo fieldInfo : fieldInfoList) {
                    subLog.log("Field: " + fieldInfo);
                }
            }
            if (methodInfoList != null) {
                for (final MethodInfo methodInfo : methodInfoList) {
                    subLog.log("Method: " + methodInfo);
                }
            }
            if (typeSignature != null) {
                ClassTypeSignature typeSig = null;
                try {
                    typeSig = ClassTypeSignature.parse(typeSignature, /* classInfo = */ null);
                } catch (final ParseException e) {
                    // Ignore
                }
                subLog.log("Class type signature: " + (typeSig == null ? typeSignature
                        : typeSig.toString(className, /* typeNameOnly = */ false, classModifiers, isAnnotation,
                                isInterface)));
            }
            if (refdClassNames != null) {
                final List<String> refdClassNamesSorted = new ArrayList<>(refdClassNames);
                Collections.sort(refdClassNamesSorted);
                subLog.log("Referenced class names: " + Join.join(", ", refdClassNamesSorted));
            }
        }
    }
}
