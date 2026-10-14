import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Offline remapping tool for building the AE2 wildcard encoder mod without ForgeGradle.
 *
 * Background: in 1.20.1 Forge production, Minecraft classes keep their <b>official (Mojang)</b> names
 * while methods and fields are renamed to SRG names (m_/f_). This tool
 *
 *   1. joins Mojang's ProGuard mappings (official <-> obf) with Forge's joined.tsrg (obf <-> srg)
 *      into official -> srg and srg -> official member mappings,
 *   2. rewrites the Forge-patched SRG Minecraft jar into an official-member jar used as the compile
 *      classpath,
 *   3. reobfuscates compiled mod classes from official to SRG member names (what FG's reobfJar does),
 *   4. verifies that every member reference of a jar really exists in the reference jars.
 *
 * Usage:
 *   mkmap        <client-mappings.txt> <joined.tsrg> <outMapDir>
 *   mc2official  <srgClient.jar> <mapDir> <outOfficial.jar>
 *   reobf        <classesDir> <officialMc.jar> <mapDir> <outDir> [extraClasspathJars...]
 *   verify       <jar> <srgClient.jar> <referenceJars...>
 */
public final class McRemap {

    // ---------------------------------------------------------------- mappings

    private static final Map<String, String> officialToObfClass = new HashMap<>();
    private static final Map<String, String> obfToOfficialClass = new HashMap<>();
    private static final Map<String, String> fieldSrg = new HashMap<>();   // obfOwner|obfName -> srg
    private static final Map<String, String> methodSrg = new HashMap<>();  // obfOwner|obfName|obfDesc -> srg

    /** owner|officialName|officialDesc -> srgName */
    private static final Map<String, String> official2srg = new HashMap<>();
    /** owner|srgName|officialDesc -> officialName */
    private static final Map<String, String> srg2official = new HashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: mkmap|mc2official|reobf|verify ...");
            System.exit(2);
        }
        switch (args[0]) {
            case "mkmap" -> mkmap(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            case "mc2official" -> mc2official(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                    Arrays.copyOfRange(args, 4, args.length));
            case "applyat" -> applyAt(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
            case "reobf" -> reobf(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]),
                    Arrays.copyOfRange(args, 5, args.length));
            case "verify" -> verify(Path.of(args[1]), Path.of(args[2]),
                    Arrays.copyOfRange(args, 3, args.length));
            default -> throw new IllegalArgumentException("unknown mode " + args[0]);
        }
    }

    // ---------------------------------------------------------------- mode 1

    private static void mkmap(Path proguard, Path tsrg, Path outDir) throws IOException {
        loadProguard(proguard);
        loadTsrg(tsrg);
        System.out.printf("proguard: %d classes, tsrg: %d fields, %d methods%n",
                officialToObfClass.size(), fieldSrg.size(), methodSrg.size());

        int methods = 0;
        int fields = 0;
        try (BufferedReader reader = Files.newBufferedReader(proguard, StandardCharsets.UTF_8)) {
            String line;
            String officialOwner = null;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith("    ")) {
                    int arrow = line.lastIndexOf(" -> ");
                    officialOwner = arrow > 0
                            ? line.substring(0, arrow).trim().replace('.', '/')
                            : null;
                    continue;
                }
                if (officialOwner == null) {
                    continue;
                }
                String body = line.trim();
                int arrow = body.lastIndexOf(" -> ");
                if (arrow < 0) {
                    continue;
                }
                String left = body.substring(0, arrow).trim();
                String obfName = body.substring(arrow + 4).trim();
                if (left.matches("\\d+:\\d+:.*")) {
                    left = left.replaceFirst("^\\d+:\\d+:", "");
                }
                String obfOwner = officialToObfClass.get(officialOwner);
                if (obfOwner == null) {
                    continue;
                }
                boolean isMethod = left.indexOf('(') >= 0;
                String officialDesc;
                String officialName;
                if (isMethod) {
                    int paren = left.indexOf('(');
                    int close = left.lastIndexOf(')');
                    if (close < paren) {
                        continue;
                    }
                    String head = left.substring(0, paren).trim();
                    int space = head.lastIndexOf(' ');
                    if (space < 0) {
                        continue;
                    }
                    officialName = head.substring(space + 1).trim();
                    String returnType = head.substring(0, space).trim();
                    String argText = left.substring(paren + 1, close).trim();
                    StringBuilder desc = new StringBuilder("(");
                    if (!argText.isEmpty()) {
                        for (String arg : argText.split(",")) {
                            desc.append(typeToDesc(arg.trim()));
                        }
                    }
                    desc.append(')').append(typeToDesc(returnType));
                    officialDesc = desc.toString();
                } else {
                    int space = left.lastIndexOf(' ');
                    if (space < 0) {
                        continue;
                    }
                    officialName = left.substring(space + 1).trim();
                    officialDesc = typeToDesc(left.substring(0, space).trim());
                }
                String obfDesc = remapDesc(obfOwner, officialDesc, McRemap::officialToObfClass);
                String srg;
                if (isMethod) {
                    srg = methodSrg.get(obfOwner + "|" + obfName + "|" + obfDesc);
                } else {
                    srg = fieldSrg.get(obfOwner + "|" + obfName);
                }
                if (srg == null) {
                    continue;
                }
                official2srg.put(officialOwner + "|" + officialName + "|" + officialDesc, srg);
                srg2official.put(officialOwner + "|" + srg + "|" + officialDesc, officialName);
                if (isMethod) {
                    methods++;
                } else {
                    fields++;
                }
            }
        }
        Files.createDirectories(outDir);
        writeMap(outDir.resolve("official2srg.tsv"), official2srg);
        writeMap(outDir.resolve("srg2official.tsv"), srg2official);
        System.out.printf("mapped members: %d methods, %d fields (%d / %d entries)%n",
                methods, fields, official2srg.size(), srg2official.size());

        // sanity probes so a broken join is obvious
        probe("net/minecraft/world/item/ItemStack", "()I", "getCount", "m_41745_");
        probe("net/minecraft/world/level/Level", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", "getBlockState", "m_8055_");
        probe("net/minecraft/world/entity/player/Player", "()Lnet/minecraft/world/entity/player/Inventory;", "getInventory", "m_150109_");
    }

    private static void probe(String owner, String desc, String officialName, String expectedSrg) {
        String srg = official2srg.get(owner + "|" + officialName + "|" + desc);
        String official = srg2official.get(owner + "|" + expectedSrg + "|" + desc);
        System.out.printf("  probe %s.%s%s -> %s (expected %s) | reverse(%s) -> %s%n",
                owner, officialName, desc, srg, expectedSrg, expectedSrg, official);
    }

    private static String officialToObfClass(String official) {
        return officialToObfClass.getOrDefault(official, official);
    }

    private static void writeMap(Path path, Map<String, String> map) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (var entry : map.entrySet()) {
                writer.write(entry.getKey());
                writer.write('\t');
                writer.write(entry.getValue());
                writer.write('\n');
            }
        }
    }

    private interface ClassNameMapper {
        String map(String name);
    }

    private static void loadProguard(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("    ")) {
                    continue;
                }
                int arrow = line.lastIndexOf(" -> ");
                if (arrow < 0 || !line.endsWith(":")) {
                    continue;
                }
                String official = line.substring(0, arrow).trim().replace('.', '/');
                String obf = line.substring(arrow + 4, line.length() - 1).trim().replace('.', '/');
                officialToObfClass.put(official, obf);
                obfToOfficialClass.put(obf, official);
            }
        }
    }

    private static void loadTsrg(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            String obfClass = null;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("tsrg2")) {
                    continue;
                }
                if (line.charAt(0) == '\t') {
                    if (obfClass == null) {
                        continue;
                    }
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 0 || parts[0].isEmpty() || isNumber(parts[0])) {
                        continue;
                    }
                    if (parts.length == 2) {
                        fieldSrg.put(obfClass + "|" + parts[0], parts[1]);
                    } else if (parts.length == 3) {
                        fieldSrg.put(obfClass + "|" + parts[0], parts[1]);
                    } else if (parts.length >= 4) {
                        methodSrg.put(obfClass + "|" + parts[0] + "|" + parts[1], parts[2]);
                    }
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    obfClass = parts[0];
                }
            }
        }
    }

    private static boolean isNumber(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String typeToDesc(String type) {
        int arrays = 0;
        while (type.endsWith("[]")) {
            arrays++;
            type = type.substring(0, type.length() - 2).trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[".repeat(arrays));
        switch (type) {
            case "void" -> sb.append('V');
            case "int" -> sb.append('I');
            case "long" -> sb.append('J');
            case "short" -> sb.append('S');
            case "byte" -> sb.append('B');
            case "char" -> sb.append('C');
            case "boolean" -> sb.append('Z');
            case "float" -> sb.append('F');
            case "double" -> sb.append('D');
            default -> sb.append('L').append(type.replace('.', '/')).append(';');
        }
        return sb.toString();
    }

    private static String remapDesc(String owner, String desc, ClassNameMapper mapper) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) {
                    throw new IllegalArgumentException("bad descriptor " + desc);
                }
                String name = desc.substring(i + 1, end);
                out.append('L').append(mapper.map(name)).append(';');
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- mode 2

    private static void mc2official(Path srgJar, Path mapDir, Path outJar, String[] hierarchyJars)
            throws IOException {
        Map<String, String> map = readMap(mapDir.resolve("srg2official.tsv"));
        System.out.printf("srg->official entries: %d%n", map.size());
        Hierarchy hierarchy = new Hierarchy();
        // The jar being remapped only declares its own classes; extra jars (for example the full
        // vanilla jar when remapping Forge's patch overlay) provide the rest of the hierarchy.
        for (String extra : hierarchyJars) {
            try (ZipFile zip = new ZipFile(extra)) {
                hierarchy.scanZip(zip, false);
            }
        }
        try (ZipFile zip = new ZipFile(srgJar.toFile())) {
            hierarchy.scanZip(zip, false);
        }
        Remap remap = new Remap(map, hierarchy);
        int classes = 0;
        try (ZipFile zip = new ZipFile(srgJar.toFile());
                ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outJar))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] data = in.readAllBytes();
                    if (entry.getName().endsWith(".class")) {
                        ClassReader reader = new ClassReader(data);
                        ClassWriter writer = new ClassWriter(0);
                        reader.accept(new ClassRemapper(writer, remap), 0);
                        data = writer.toByteArray();
                        classes++;
                    }
                    ZipEntry copy = new ZipEntry(entry.getName());
                    copy.setTime(0L);
                    out.putNextEntry(copy);
                    out.write(data);
                    out.closeEntry();
                }
            }
        }
        System.out.printf("wrote %s (%d classes remapped)%n", outJar, classes);
    }

    // ---------------------------------------------------------------- mode 3

    private static void reobf(Path classesDir, Path officialMcJar, Path mapDir, Path outDir,
            String[] extraJars) throws IOException {
        Map<String, String> map = readMap(mapDir.resolve("official2srg.tsv"));
        System.out.printf("official->srg entries: %d%n", map.size());

        Hierarchy hierarchy = new Hierarchy();
        try (ZipFile zip = new ZipFile(officialMcJar.toFile())) {
            hierarchy.scanZip(zip, false);
        }
        for (String extra : extraJars) {
            try (ZipFile zip = new ZipFile(extra)) {
                hierarchy.scanZip(zip, false);
            }
        }
        hierarchy.scanDirectory(classesDir, false);

        Remap remap = new Remap(map, hierarchy);
        Files.createDirectories(outDir);
        List<Path> classFiles = new ArrayList<>();
        try (var stream = Files.walk(classesDir)) {
            stream.filter(p -> p.toString().endsWith(".class")).forEach(classFiles::add);
        }
        for (Path file : classFiles) {
            byte[] data = Files.readAllBytes(file);
            ClassReader reader = new ClassReader(data);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, remap), 0);
            Path target = outDir.resolve(classesDir.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.write(target, writer.toByteArray());
        }
        System.out.printf("reobfuscated %d classes -> %s%n", classFiles.size(), outDir);
    }

    // ---------------------------------------------------------------- mode 4

    private static void verify(Path jar, Path srgJar, String[] referenceJars) throws IOException {
        Hierarchy hierarchy = new Hierarchy();
        Map<String, Set<String>> declared = new HashMap<>();   // owner -> name+desc (methods+fields)
        Set<String> knownClasses = new HashSet<>();

        indexJar(srgJar, hierarchy, declared, knownClasses);
        for (String reference : referenceJars) {
            indexJar(Path.of(reference), hierarchy, declared, knownClasses);
        }

        List<String> problems = new ArrayList<>();
        int checked = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                ClassReader reader = new ClassReader(data);
                List<String[]> refs = new ArrayList<>();
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        if (exceptions != null) {
                            for (String ex : exceptions) {
                                refs.add(new String[] { "class", ex, null, null });
                            }
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                refs.add(new String[] { "class", type, null, null });
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                                refs.add(new String[] { "field", owner, name, desc });
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name, String desc,
                                    boolean isInterface) {
                                refs.add(new String[] { "method", owner, name, desc });
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String name, String desc, Handle bsm,
                                    Object... args) {
                                refs.add(new String[] { "method", bsm.getOwner(), bsm.getName(), bsm.getDesc() });
                                for (Object arg : args) {
                                    if (arg instanceof Type type && type.getSort() == Type.OBJECT) {
                                        refs.add(new String[] { "class", type.getInternalName(), null, null });
                                    }
                                    if (arg instanceof Handle handle) {
                                        refs.add(new String[] { "method", handle.getOwner(), handle.getName(),
                                                handle.getDesc() });
                                    }
                                }
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                                    refs.add(new String[] { "class", type.getInternalName(), null, null });
                                }
                            }
                        };
                    }
                }, 0);

                for (String[] ref : refs) {
                    String kind = ref[0];
                    String owner = ref[1];
                    if (owner == null || owner.startsWith("[")) {
                        continue;
                    }
                    if (!isGameClass(owner)) {
                        continue;
                    }
                    checked++;
                    if (!knownClasses.contains(owner)) {
                        problems.add(entry.getName() + ": unknown class " + owner);
                        continue;
                    }
                    if ("class".equals(kind)) {
                        continue;
                    }
                    String key = ref[2] + "|" + ref[3];
                    if (!declares(hierarchy, declared, owner, key)) {
                        problems.add(entry.getName() + ": " + kind + " " + owner + "." + ref[2] + ref[3]
                                + " not found");
                    }
                }
            }
        }
        System.out.printf("checked %d references in %s, %d problem(s)%n", checked, jar, problems.size());
        for (String problem : problems.stream().distinct().limit(60).toList()) {
            System.out.println("  " + problem);
        }
        if (!problems.isEmpty()) {
            System.exit(1);
        }
    }

    private static boolean isGameClass(String owner) {
        return owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/")
                || owner.startsWith("net/minecraftforge/") || owner.startsWith("appeng/");
    }

    private static boolean declares(Hierarchy hierarchy, Map<String, Set<String>> declared, String owner,
            String key) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            Set<String> members = declared.get(current);
            if (members != null && members.contains(key)) {
                return true;
            }
            if (members == null && declaresInJdk(current, key)) {
                // methods inherited from java.* interfaces (Iterable.iterator, List.add, ...)
                return true;
            }
            String[] supers = hierarchy.supertypes.get(current);
            if (supers != null) {
                queue.addAll(Arrays.asList(supers));
            }
        }
        return false;
    }

    /** Fallback for members inherited from the JDK (the reference jars obviously do not contain them). */
    private static boolean declaresInJdk(String internalName, String key) {
        if (internalName.startsWith("java/") || internalName.startsWith("javax/")) {
            // already a JDK type - handled below
        } else if (internalName.isEmpty()) {
            return false;
        }
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false,
                    McRemap.class.getClassLoader());
            String name = key.substring(0, key.indexOf('|'));
            for (var method : type.getMethods()) {
                if (method.getName().equals(name)) {
                    return true;
                }
            }
            for (var field : type.getFields()) {
                if (field.getName().equals(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // not on this tool's classpath (a Minecraft class) - the hierarchy walk handles those
        }
        return false;
    }

    private static void indexJar(Path jar, Hierarchy hierarchy, Map<String, Set<String>> declared,
            Set<String> knownClasses) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            hierarchy.scanZip(zip, false);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(in.readAllBytes());
                    String owner = reader.getClassName();
                    knownClasses.add(owner);
                    Set<String> members = declared.computeIfAbsent(owner, k -> new HashSet<>());
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            members.add(name + "|" + descriptor);
                            return null;
                        }

                        @Override
                        public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                String signature, Object value) {
                            members.add(name + "|" + descriptor);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
    }

    // ---------------------------------------------------------------- access transformers

    /**
     * Applies Forge's accesstransformer.cfg to a jar.
     *
     * <p>Forge's AT entries name members with their <b>SRG</b> names (m_/f_) but official class names,
     * exactly like the {@code srg2official} map, so the member names can be translated back to the
     * official names used by the remapped development jar. Without this step the offline compile
     * classpath would be missing the widened visibility that ForgeGradle grants to mod developers
     * (for example {@code BlockEntityType$BlockEntitySupplier}).
     */
    private static void applyAt(Path inJar, Path atFile, Path mapDir, Path outJar) throws IOException {
        Map<String, String> srg2official = readMap(mapDir.resolve("srg2official.tsv"));

        Map<String, String> classAccess = new HashMap<>();               // owner -> "public|-f"
        Map<String, Map<String, String>> memberAccess = new HashMap<>(); // owner -> memberKey -> "public|-f"
        int resolved = 0;
        int unresolved = 0;

        for (String raw : Files.readAllLines(atFile, StandardCharsets.UTF_8)) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String accessToken = parts[0];
            int finalMode = 0;
            if (accessToken.endsWith("+f")) {
                accessToken = accessToken.substring(0, accessToken.length() - 2);
                finalMode = 1;
            } else if (accessToken.endsWith("-f")) {
                accessToken = accessToken.substring(0, accessToken.length() - 2);
                finalMode = -1;
            }
            String spec = accessToken + (finalMode == 0 ? "" : (finalMode > 0 ? "+f" : "-f"));
            String owner = parts[1].replace('.', '/');
            if (parts.length == 2) {
                classAccess.put(owner, spec);
                continue;
            }
            String member = parts[2];
            String srgName = member;
            String desc = null;
            int paren = member.indexOf('(');
            if (paren >= 0) {
                srgName = member.substring(0, paren);
                desc = member.substring(paren);
            }
            String officialName = null;
            if (desc != null) {
                officialName = srg2official.get(owner + "|" + srgName + "|" + desc);
            } else {
                String prefix = owner + "|" + srgName + "|";
                for (var entry : srg2official.entrySet()) {
                    if (entry.getKey().startsWith(prefix)) {
                        officialName = entry.getValue();
                        break;
                    }
                }
            }
            if (officialName == null) {
                unresolved++;
                continue;
            }
            resolved++;
            memberAccess.computeIfAbsent(owner, k -> new HashMap<>())
                    .put(officialName + "|" + (desc == null ? "-" : desc), spec);
        }
        System.out.printf("AT: %d classes, %d members resolved, %d unresolved%n",
                classAccess.size(), resolved, unresolved);

        int changed = 0;
        try (ZipFile zip = new ZipFile(inJar.toFile());
                ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outJar))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(0);
                    Map<String, String> members = memberAccess.getOrDefault(reader.getClassName(), Map.of());
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public void visit(int version, int access, String name, String signature, String superName,
                                String[] interfaces) {
                            String spec = classAccess.get(name);
                            super.visit(version, spec == null ? access : widen(access, spec), name, signature,
                                    superName, interfaces);
                        }

                        @Override
                        public void visitInnerClass(String name, String outerName, String innerName, int access) {
                            // javac reads nested type visibility from the InnerClasses attribute, which is
                            // stored in the inner class itself *and* in every class referencing it
                            String spec = classAccess.get(name);
                            super.visitInnerClass(name, outerName, innerName,
                                    spec == null ? access : widen(access, spec));
                        }

                        @Override
                        public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                String signature, Object value) {
                            String spec = members.get(name + "|-");
                            return super.visitField(spec == null ? access : widen(access, spec), name, descriptor,
                                    signature, value);
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            String spec = members.get(name + "|" + descriptor);
                            return super.visitMethod(spec == null ? access : widen(access, spec), name, descriptor,
                                    signature, exceptions);
                        }
                    }, 0);
                    data = writer.toByteArray();
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0L);
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.printf("wrote %s%n", outJar);
    }

    private static int widen(int access, String spec) {
        boolean removeFinal = spec.endsWith("-f");
        boolean addFinal = spec.endsWith("+f");
        String kind = spec;
        if (removeFinal || addFinal) {
            kind = spec.substring(0, spec.length() - 2);
        }
        int result = access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
        switch (kind) {
            case "public" -> result |= Opcodes.ACC_PUBLIC;
            case "protected" -> result |= Opcodes.ACC_PROTECTED;
            case "private" -> result |= Opcodes.ACC_PRIVATE;
            default -> {
                // "default" = package private, already cleared
            }
        }
        if (removeFinal) {
            result &= ~Opcodes.ACC_FINAL;
        }
        if (addFinal) {
            result |= Opcodes.ACC_FINAL;
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> readMap(Path path) throws IOException {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    map.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return map;
    }

    /** supertypes of every class, in whatever namespace the scanned jar uses. */
    static final class Hierarchy {
        final Map<String, String[]> supertypes = new HashMap<>();
        final Set<String> classes = new HashSet<>();

        void record(String owner, String superName, String[] interfaces) {
            classes.add(owner);
            List<String> all = new ArrayList<>();
            if (superName != null && !"java/lang/Object".equals(superName)) {
                all.add(superName);
            } else if (superName != null) {
                all.add(superName);
            }
            if (interfaces != null) {
                all.addAll(Arrays.asList(interfaces));
            }
            String[] previous = supertypes.get(owner);
            if (previous == null || previous.length < all.size()) {
                supertypes.put(owner, all.toArray(new String[0]));
            }
        }

        void scanZip(ZipFile zip, boolean skipCode) throws IOException {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(in.readAllBytes());
                    record(reader.getClassName(), reader.getSuperName(), reader.getInterfaces());
                }
            }
        }

        void scanDirectory(Path dir, boolean skipCode) throws IOException {
            try (var stream = Files.walk(dir)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                    ClassReader reader = new ClassReader(Files.readAllBytes(file));
                    record(reader.getClassName(), reader.getSuperName(), reader.getInterfaces());
                }
            }
        }
    }

    /**
     * Member-name remapper with hierarchy aware lookups. Owner aware first, then the supertype chain -
     * the supertype walk is what makes overridden methods in our own classes (and inherited call
     * sites) resolve to the right SRG name.
     */
    static final class Remap extends Remapper {
        private final Map<String, String> byOwnerKey;
        private final Map<String, String> byOwnerName;
        private final Set<String> ambiguousOwnerNames;
        private final Hierarchy hierarchy;

        Remap(Map<String, String> byOwnerKey, Hierarchy hierarchy) {
            this.byOwnerKey = byOwnerKey;
            this.hierarchy = hierarchy;
            // Lambda bootstrap names (invokedynamic) are looked up without a descriptor, so keep an
            // owner+name index for the unambiguous cases (e.g. MenuProvider.createMenu).
            Map<String, String> names = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();
            for (var entry : byOwnerKey.entrySet()) {
                String key = entry.getKey();
                int lastBar = key.lastIndexOf('|');
                if (lastBar < 0) {
                    continue;
                }
                String ownerAndName = key.substring(0, lastBar);
                String existing = names.putIfAbsent(ownerAndName, entry.getValue());
                if (existing != null && !existing.equals(entry.getValue())) {
                    ambiguous.add(ownerAndName);
                }
            }
            this.byOwnerName = names;
            this.ambiguousOwnerNames = ambiguous;
        }

        String lookup(String owner, String name, String desc) {
            if (owner == null) {
                return null;
            }
            String direct = byOwnerKey.get(owner + "|" + name + "|" + desc);
            if (direct != null) {
                return direct;
            }
            for (String current : supertypes(owner)) {
                String found = byOwnerKey.get(current + "|" + name + "|" + desc);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        String lookupWithoutDescriptor(String owner, String name) {
            if (owner == null) {
                return null;
            }
            for (String current : supertypes(owner)) {
                String key = current + "|" + name;
                if (ambiguousOwnerNames.contains(key)) {
                    continue;
                }
                String found = byOwnerName.get(key);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private List<String> supertypes(String owner) {
            List<String> order = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(owner);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (current == null || !visited.add(current)) {
                    continue;
                }
                order.add(current);
                String[] supers = hierarchy.supertypes.get(current);
                if (supers != null) {
                    queue.addAll(Arrays.asList(supers));
                }
            }
            return order;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String mapped = lookup(owner, name, descriptor);
            return mapped != null ? mapped : name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String mapped = lookup(owner, name, descriptor);
            return mapped != null ? mapped : name;
        }

        /**
         * Lambdas that implement a Minecraft functional interface carry the interface method name in
         * the invokedynamic bootstrap; in production that name is SRG renamed. The descriptor's return
         * type is the functional interface.
         */
        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            Type returnType = Type.getReturnType(descriptor);
            if (returnType.getSort() == Type.OBJECT) {
                String mapped = lookupWithoutDescriptor(returnType.getInternalName(), name);
                if (mapped != null) {
                    return mapped;
                }
            }
            return name;
        }
    }
}
