/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch;

import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.Assert.assertTrue;

/**
 * Headless JUnit test that verifies all Baritone {@code @Mixin} classes, method descriptors,
 * {@code @Shadow}/{@code @Accessor}/{@code @Invoker} members, and {@code @At} instruction targets
 * against the Minecraft 26.3 bytecode on the test classpath.
 */
public class MixinAuditTest {

    private final Map<String, byte[]> classBytes = new HashMap<>();
    private final Map<String, ClassModel> modelCache = new HashMap<>();
    private final List<String> errors = new ArrayList<>();

    private ClassModel getModel(String internalName) {
        return modelCache.computeIfAbsent(internalName, k -> {
            byte[] b = classBytes.get(k);
            return b != null ? ClassFile.of().parse(b) : null;
        });
    }

    private void loadJar(Path jarPath) throws Exception {
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry je = entries.nextElement();
                if (je.getName().endsWith(".class") && !je.getName().startsWith("META-INF/")) {
                    String name = je.getName().substring(0, je.getName().length() - 6);
                    try (InputStream is = jf.getInputStream(je)) {
                        classBytes.putIfAbsent(name, is.readAllBytes());
                    }
                }
            }
        }
    }

    private void loadDir(Path dirPath) throws Exception {
        try (var stream = Files.walk(dirPath)) {
            for (Path cf : stream.filter(f -> f.toString().endsWith(".class")).toList()) {
                byte[] b = Files.readAllBytes(cf);
                ClassModel cm = ClassFile.of().parse(b);
                classBytes.putIfAbsent(cm.thisClass().asInternalName(), b);
            }
        }
    }

    private static Map<String, AnnotationValue> getAnnotationMap(Annotation ann) {
        Map<String, AnnotationValue> map = new HashMap<>();
        for (AnnotationElement el : ann.elements()) {
            map.put(el.name().stringValue(), el.value());
        }
        return map;
    }

    private static List<String> getStrings(AnnotationValue ev) {
        if (ev == null) return List.of();
        if (ev instanceof AnnotationValue.OfString s) return List.of(s.stringValue());
        if (ev instanceof AnnotationValue.OfArray a) {
            List<String> res = new ArrayList<>();
            for (AnnotationValue v : a.values()) {
                if (v instanceof AnnotationValue.OfString s) res.add(s.stringValue());
                else if (v instanceof AnnotationValue.OfClass c) res.add(c.classSymbol().descriptorString());
            }
            return res;
        }
        if (ev instanceof AnnotationValue.OfClass c) return List.of(c.classSymbol().descriptorString());
        return List.of();
    }

    private static List<Annotation> getAllAnnotations(AttributedElement el) {
        List<Annotation> list = new ArrayList<>();
        el.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> list.addAll(a.annotations()));
        el.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> list.addAll(a.annotations()));
        return list;
    }

    private List<MethodModel> findMethodsInHierarchy(String owner, String spec) {
        ClassModel cm = getModel(owner);
        if (cm == null) return List.of();
        String name = spec;
        String desc = null;
        int paren = spec.indexOf('(');
        if (paren >= 0) {
            name = spec.substring(0, paren);
            desc = spec.substring(paren);
        }
        if (name.startsWith("L") && name.contains(";")) {
            name = name.substring(name.indexOf(';') + 1);
        }
        if (name.endsWith("*")) {
            name = name.substring(0, name.length() - 1);
        }
        List<MethodModel> matches = new ArrayList<>();
        for (MethodModel mm : cm.methods()) {
            String mName = mm.methodName().stringValue();
            String mDesc = mm.methodType().stringValue();
            boolean nameMatch = mName.equals(name) || (spec.contains("*") && mName.startsWith(name));
            if (nameMatch && (desc == null || mDesc.equals(desc) || mDesc.startsWith(desc))) {
                matches.add(mm);
            }
        }
        if (matches.isEmpty()) {
            if (cm.superclass().isPresent()) {
                matches.addAll(findMethodsInHierarchy(cm.superclass().get().asInternalName(), spec));
            }
            for (ClassEntry iface : cm.interfaces()) {
                matches.addAll(findMethodsInHierarchy(iface.asInternalName(), spec));
            }
        }
        return matches;
    }

    private FieldModel findFieldModelInHierarchy(String owner, String name, String desc) {
        ClassModel cm = getModel(owner);
        if (cm == null) return null;
        for (FieldModel fm : cm.fields()) {
            if (fm.fieldName().stringValue().equals(name) && (desc == null || fm.fieldType().stringValue().equals(desc))) {
                return fm;
            }
        }
        if (cm.superclass().isPresent()) {
            FieldModel found = findFieldModelInHierarchy(cm.superclass().get().asInternalName(), name, desc);
            if (found != null) return found;
        }
        for (ClassEntry iface : cm.interfaces()) {
            FieldModel found = findFieldModelInHierarchy(iface.asInternalName(), name, desc);
            if (found != null) return found;
        }
        return null;
    }

    private boolean findFieldInHierarchy(String owner, String name, String desc) {
        return findFieldModelInHierarchy(owner, name, desc) != null;
    }

    private static boolean checkAtTargetInMethod(MethodModel mm, String atValue, String atTarget) {
        if (atTarget == null || atTarget.isEmpty()) return true;
        if (mm.code().isEmpty()) return true;
        for (CodeElement ce : mm.code().get()) {
            if (ce instanceof InvokeInstruction inv) {
                String full1 = "L" + inv.owner().asInternalName() + ";" + inv.name().stringValue() + inv.type().stringValue();
                String full2 = inv.owner().asInternalName() + "." + inv.name().stringValue() + inv.type().stringValue();
                if (full1.equals(atTarget) || full2.equals(atTarget) || full1.startsWith(atTarget)) return true;
            } else if (ce instanceof FieldInstruction fi) {
                String full1 = "L" + fi.owner().asInternalName() + ";" + fi.name().stringValue() + ":" + fi.type().stringValue();
                String full2 = fi.owner().asInternalName() + "." + fi.name().stringValue() + ":" + fi.type().stringValue();
                if (full1.equals(atTarget) || full2.equals(atTarget)) return true;
            } else if (ce instanceof NewObjectInstruction noi) {
                String c1 = "L" + noi.className().asInternalName() + ";";
                String c2 = noi.className().asInternalName();
                if (atTarget.equals(c1) || atTarget.equals(c2) || atTarget.startsWith(c1)) return true;
                if (atTarget.startsWith("(") && atTarget.endsWith(c1)) return true;
            }
        }
        return false;
    }

    private static List<String> parseParamTypes(String methodDesc) {
        List<String> params = new ArrayList<>();
        int i = 1;
        while (i < methodDesc.length() && methodDesc.charAt(i) != ')') {
            int start = i;
            while (methodDesc.charAt(i) == '[') i++;
            if (methodDesc.charAt(i) == 'L') {
                i = methodDesc.indexOf(';', i) + 1;
            } else {
                i++;
            }
            params.add(methodDesc.substring(start, i));
        }
        return params;
    }

    private void auditMixinClass(Path cf) throws Exception {
        ClassModel cm = ClassFile.of().parse(cf);
        String mixinName = cm.thisClass().asInternalName();
        List<String> targets = new ArrayList<>();
        for (Annotation ann : getAllAnnotations(cm)) {
            if (ann.className().stringValue().equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                Map<String, AnnotationValue> map = getAnnotationMap(ann);
                for (String desc : getStrings(map.get("value"))) {
                    if (desc.startsWith("L") && desc.endsWith(";")) {
                        targets.add(desc.substring(1, desc.length() - 1));
                    }
                }
                for (String t : getStrings(map.get("targets"))) {
                    targets.add(t.replace('.', '/'));
                }
            }
        }
        if (targets.isEmpty()) return;

        boolean isInterfaceMixin = cm.flags().has(AccessFlag.INTERFACE);
        for (String target : targets) {
            ClassModel tcm = getModel(target);
            if (tcm == null) {
                errors.add("[MISSING TARGET CLASS] " + mixinName + " -> " + target);
                continue;
            }
            for (FieldModel fm : cm.fields()) {
                for (Annotation ann : getAllAnnotations(fm)) {
                    if (ann.className().stringValue().equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                        String fName = fm.fieldName().stringValue();
                        String fDesc = fm.fieldType().stringValue();
                        FieldModel tfm = findFieldModelInHierarchy(target, fName, fDesc);
                        if (tfm == null) {
                            errors.add("[MISSING @Shadow FIELD] " + mixinName + " in " + target + ": " + fName + " " + fDesc);
                        } else if (tfm.flags().has(AccessFlag.STATIC) != fm.flags().has(AccessFlag.STATIC)) {
                            errors.add("[MISMATCHED @Shadow FIELD STATIC] " + mixinName + "#" + fName + " in " + target);
                        }
                    }
                }
            }

            Set<String> satisfiedGroups = new HashSet<>();
            for (MethodModel mm : cm.methods()) {
                String groupName = null;
                for (Annotation ann : getAllAnnotations(mm)) {
                    if (ann.className().stringValue().equals("Lorg/spongepowered/asm/mixin/injection/Group;")) {
                        List<String> gNames = getStrings(getAnnotationMap(ann).get("name"));
                        if (!gNames.isEmpty()) groupName = gNames.get(0);
                    }
                }
                if (groupName == null) continue;
                for (Annotation ann : getAllAnnotations(mm)) {
                    String annType = ann.className().stringValue();
                    if (annType.startsWith("Lorg/spongepowered/asm/mixin/injection/")) {
                        Map<String, AnnotationValue> map = getAnnotationMap(ann);
                        for (AnnotationValue v : map.get("at") instanceof AnnotationValue.OfArray arr ? arr.values() : (map.get("at") != null ? List.of(map.get("at")) : List.<AnnotationValue>of())) {
                            if (v instanceof AnnotationValue.OfAnnotation oa) {
                                Map<String, AnnotationValue> atMap = getAnnotationMap(oa.annotation());
                                List<String> atVals = getStrings(atMap.get("value"));
                                List<String> atTargets = getStrings(atMap.get("target"));
                                String atValue = atVals.isEmpty() ? "" : atVals.get(0);
                                String atTarget = atTargets.isEmpty() ? "" : atTargets.get(0);
                                for (String spec : getStrings(map.get("method"))) {
                                    for (MethodModel tm : findMethodsInHierarchy(target, spec)) {
                                        if (checkAtTargetInMethod(tm, atValue, atTarget)) {
                                            satisfiedGroups.add(groupName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            for (MethodModel mm : cm.methods()) {
                String methodGroup = null;
                for (Annotation ann : getAllAnnotations(mm)) {
                    if (ann.className().stringValue().equals("Lorg/spongepowered/asm/mixin/injection/Group;")) {
                        List<String> gNames = getStrings(getAnnotationMap(ann).get("name"));
                        if (!gNames.isEmpty()) methodGroup = gNames.get(0);
                    }
                }
                for (Annotation ann : getAllAnnotations(mm)) {
                    String annType = ann.className().stringValue();
                    Map<String, AnnotationValue> map = getAnnotationMap(ann);
                    if (annType.equals("Lorg/spongepowered/asm/mixin/Shadow;") || annType.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                        String mName = mm.methodName().stringValue();
                        String mDesc = mm.methodType().stringValue();
                        List<MethodModel> shadowTargets = findMethodsInHierarchy(target, mName + mDesc);
                        if (shadowTargets.isEmpty()) {
                            errors.add("[MISSING " + (annType.contains("Overwrite") ? "@Overwrite" : "@Shadow") + " METHOD] " + mixinName + " in " + target + ": " + mName + mDesc);
                        } else if (!isInterfaceMixin) {
                            boolean handlerStatic = mm.flags().has(AccessFlag.STATIC);
                            boolean anyMatch = false;
                            for (MethodModel tm : shadowTargets) {
                                if (tm.flags().has(AccessFlag.STATIC) == handlerStatic) {
                                    anyMatch = true;
                                    break;
                                }
                            }
                            if (!anyMatch) {
                                errors.add("[MISMATCHED @Shadow/@Overwrite STATIC] " + mixinName + "#" + mName + mDesc + " in " + target);
                            }
                        }
                    } else if (annType.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                        String mName = mm.methodName().stringValue();
                        List<String> val = getStrings(map.get("value"));
                        String fName = val.isEmpty() ? "" : val.get(0);
                        if (fName.isEmpty()) {
                            String stripped = mName.contains("$") ? mName.substring(mName.indexOf('$') + 1) : mName;
                            if (stripped.startsWith("get") || stripped.startsWith("set")) stripped = stripped.substring(3);
                            else if (stripped.startsWith("is")) stripped = stripped.substring(2);
                            if (!stripped.isEmpty()) {
                                fName = Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
                            }
                        }
                        if (!fName.isEmpty() && !findFieldInHierarchy(target, fName, null) && !findFieldInHierarchy(target, fName.toUpperCase(Locale.ROOT), null)) {
                            errors.add("[MISSING @Accessor FIELD] " + mixinName + "#" + mName + " in " + target + ": field=\"" + fName + "\"");
                        }
                    } else if (annType.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
                        String mName = mm.methodName().stringValue();
                        List<String> val = getStrings(map.get("value"));
                        String iName = val.isEmpty() ? "" : val.get(0);
                        if (iName.isEmpty()) {
                            String stripped = mName.contains("$") ? mName.substring(mName.indexOf('$') + 1) : mName;
                            if (stripped.startsWith("invoke") || stripped.startsWith("call")) {
                                int prefixLen = stripped.startsWith("invoke") ? 6 : 4;
                                stripped = stripped.substring(prefixLen);
                                if (!stripped.isEmpty()) {
                                    iName = Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
                                }
                            }
                        }
                        if (!iName.isEmpty() && findMethodsInHierarchy(target, iName + mm.methodType().stringValue()).isEmpty()) {
                            errors.add("[MISSING @Invoker METHOD] " + mixinName + "#" + mName + " in " + target + ": " + iName + mm.methodType().stringValue());
                        }
                    } else if (annType.startsWith("Lorg/spongepowered/asm/mixin/injection/")) {
                        if (map.get("require") instanceof AnnotationValue.OfInt oi && oi.intValue() == 0) {
                            continue;
                        }
                        List<String> methodSpecs = getStrings(map.get("method"));
                        for (String spec : methodSpecs) {
                            List<MethodModel> matchedMethods = findMethodsInHierarchy(target, spec);
                            if (matchedMethods.isEmpty()) {
                                if (methodGroup != null && satisfiedGroups.contains(methodGroup)) {
                                    continue;
                                }
                                errors.add("[MISSING INJECT TARGET METHOD] " + mixinName + "#" + mm.methodName().stringValue() + " -> " + target + " method=\"" + spec + "\"");
                            } else {
                                if (!isInterfaceMixin) {
                                    boolean handlerStatic = mm.flags().has(AccessFlag.STATIC);
                                    for (MethodModel tm : matchedMethods) {
                                        String tmName = tm.methodName().stringValue();
                                        if (tmName.equals("<init>") || tmName.equals("<clinit>")) continue;
                                        if (tm.flags().has(AccessFlag.STATIC) != handlerStatic) {
                                            errors.add("[MISMATCHED INJECT STATIC MODIFIER] " + mixinName + "#" + mm.methodName().stringValue()
                                                + " (static=" + handlerStatic + ") -> " + target + "#" + tmName + tm.methodType().stringValue()
                                                + " (static=" + tm.flags().has(AccessFlag.STATIC) + ")");
                                        }
                                    }
                                }
                                if (annType.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                                    List<String> hParams = parseParamTypes(mm.methodType().stringValue());
                                    int ciIdx = -1;
                                    for (int i = 0; i < hParams.size(); i++) {
                                        if (hParams.get(i).startsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
                                            ciIdx = i;
                                            break;
                                        }
                                    }
                                    if (ciIdx > 0) {
                                        List<String> prefixParams = hParams.subList(0, ciIdx);
                                        boolean sigOk = false;
                                        for (MethodModel tm : matchedMethods) {
                                            List<String> tParams = parseParamTypes(tm.methodType().stringValue());
                                            if (tParams.size() >= prefixParams.size() && tParams.subList(0, prefixParams.size()).equals(prefixParams)) {
                                                sigOk = true;
                                                break;
                                            }
                                        }
                                        if (!sigOk) {
                                            errors.add("[MISMATCHED @Inject PARAMS] " + mixinName + "#" + mm.methodName().stringValue() + mm.methodType().stringValue() + " vs " + target + "." + spec + " -> " + matchedMethods.get(0).methodType().stringValue());
                                        }
                                    }
                                }
                                List<Annotation> atAnns = new ArrayList<>();
                                AnnotationValue atVal = map.get("at");
                                if (atVal instanceof AnnotationValue.OfAnnotation oa) atAnns.add(oa.annotation());
                                else if (atVal instanceof AnnotationValue.OfArray arr) {
                                    for (AnnotationValue v : arr.values()) {
                                        if (v instanceof AnnotationValue.OfAnnotation oa) atAnns.add(oa.annotation());
                                    }
                                }
                                for (Annotation atAnn : atAnns) {
                                    Map<String, AnnotationValue> atMap = getAnnotationMap(atAnn);
                                    List<String> atVals = getStrings(atMap.get("value"));
                                    List<String> atTargets = getStrings(atMap.get("target"));
                                    String atValue = atVals.isEmpty() ? "" : atVals.get(0);
                                    String atTarget = atTargets.isEmpty() ? "" : atTargets.get(0);
                                    if (!atTarget.isEmpty() && (atValue.equals("INVOKE") || atValue.equals("FIELD") || atValue.equals("NEW") || atValue.equals("INVOKE_ASSIGN"))) {
                                        boolean foundInAny = false;
                                        for (MethodModel tm : matchedMethods) {
                                            if (checkAtTargetInMethod(tm, atValue, atTarget)) {
                                                foundInAny = true;
                                                break;
                                            }
                                        }
                                        if (!foundInAny) {
                                            if (methodGroup != null && satisfiedGroups.contains(methodGroup)) {
                                                continue;
                                            }
                                            errors.add("[MISSING @At TARGET] " + mixinName + "#" + mm.methodName().stringValue() + " in " + target + "." + spec + " -> @At(" + atValue + ", target=\"" + atTarget + "\")");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testAllMixinsResolveAgainstMinecraft26_3() throws Exception {
        List<Path> launchDirs = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path p = Path.of(entry);
            if (Files.isRegularFile(p) && entry.endsWith(".jar")) {
                loadJar(p);
            } else if (Files.isDirectory(p)) {
                loadDir(p);
                if (entry.replace('\\', '/').contains("/classes/java/launch")) {
                    launchDirs.add(p);
                }
            }
        }

        int mixinCount = 0;
        for (Path dir : launchDirs) {
            try (var stream = Files.walk(dir)) {
                for (Path cf : stream.filter(f -> f.toString().endsWith(".class")).toList()) {
                    auditMixinClass(cf);
                    mixinCount++;
                }
            }
        }

        assertTrue("Expected Baritone launch classes on test classpath", mixinCount > 0);
        assertTrue("Mixin verification errors:\n" + String.join("\n", errors), errors.isEmpty());
    }
}
