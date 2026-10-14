#!/usr/bin/env python3
"""Offline builder for the AE2 Wildcard Pattern Encoder mod jar.

This is the offline build path: the jar is produced with plain javac plus the McRemap tool, against
the Minecraft/Forge artifacts that the launcher and ForgeGradle caches already contain. Handy when
Gradle cannot run (no network, sandboxed shell, cold CI cache); normal development should use Gradle.

Pipeline:
  maps      join Mojang ProGuard mappings with Forge's joined.tsrg  (McRemap mkmap)
  mc        rewrite the Forge-patched SRG Minecraft jar to official member names (McRemap mc2official)
  compile   javac the mod sources against that jar + Forge + AE2
  reobf     official -> SRG member names (McRemap reobf) - what ForgeGradle's reobfJar does
  package   build/offline/wildcardencoder-<version>.jar with resources and manifest
  verify    every Minecraft/Forge/AE2 member reference must exist in the real jars (McRemap verify)

Usage:  python tools/dev/build_offline.py [all|maps|mc|compile|reobf|package|verify ...]
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

PROJ = Path(__file__).resolve().parents[2]
DEV = PROJ / "tools" / "dev"
OUT = PROJ / "build" / "offline"
JDK_HOME = Path(os.environ.get("JAVA17_HOME")
        or os.environ.get("JAVA_HOME")
        or (Path.home() / ".jdks" / "17"))
JAVA = JDK_HOME / "bin" / "java.exe"
JAVAC = JDK_HOME / "bin" / "javac.exe"

MC_VERSION = "1.20.1"
FORGE_VERSION = "1.20.1-47.4.20"
MC_TS = "1.20.1-20230612.114412"
FORGE_GRADLE_CACHE = Path(os.environ.get("GRADLE_CACHE", Path.home() / ".gradle" / "caches"))
LIBS = Path(os.environ.get("MC_LIBRARIES")
        or (Path.home() / "AppData" / "Roaming" / ".minecraft" / "libraries"))

SRG_JAR = LIBS / "net/minecraft/client" / MC_TS / f"client-{MC_TS}-srg.jar"
FORGE_CLIENT_JAR = LIBS / "net/minecraftforge" / "forge" / FORGE_VERSION / f"forge-{FORGE_VERSION}-client.jar"
PROGUARD = LIBS / "net/minecraft/client" / MC_TS / f"client-{MC_TS}-mappings.txt"
MCP_CONFIG = (FORGE_GRADLE_CACHE / "forge_gradle" / "maven_downloader" / "de" / "oceanlabs" / "mcp"
              / "mcp_config" / MC_TS / f"mcp_config-{MC_TS}.zip")
TSRG = OUT / "joined.tsrg"
USERDEV_JAR = (FORGE_GRADLE_CACHE / "forge_gradle" / "maven_downloader" / "net" / "minecraftforge" / "forge"
               / FORGE_VERSION / f"forge-{FORGE_VERSION}-userdev.jar")
MC_OFFICIAL = OUT / "mc-official.jar"
# Forge ships its patches to Minecraft as a separate jar that overrides the vanilla classes at
# runtime; it has to come first on the compile classpath (that is where e.g. the public
# MenuScreens.register and BlockEntity.getCapability live).
PATCHED_OFFICIAL = OUT / "forge-patched-official.jar"
# Forge's access transformers widen members for mod developers (for example
# BlockEntityType$BlockEntitySupplier); the launcher jars keep the vanilla visibility, so the
# transformers have to be applied to build the development compile classpath.
AT_CONFIG = OUT / "accesstransformer.cfg"
MC_OFFICIAL_AT = OUT / "mc-official-at.jar"
PATCHED_OFFICIAL_AT = OUT / "forge-patched-official-at.jar"
MAP_DIR = OUT / "map"
CLASSES = OUT / "classes"
REOBF = OUT / "reobf"

# Forge + runtime libraries that the launcher already installed for 1.20.1 Forge.
LIBRARY_JARS = [
    f"net/minecraftforge/forge/{FORGE_VERSION}/forge-{FORGE_VERSION}-universal.jar",
    f"net/minecraftforge/fmlcore/{FORGE_VERSION}/fmlcore-{FORGE_VERSION}.jar",
    f"net/minecraftforge/fmlloader/{FORGE_VERSION}/fmlloader-{FORGE_VERSION}.jar",
    f"net/minecraftforge/fmlearlydisplay/{FORGE_VERSION}/fmlearlydisplay-{FORGE_VERSION}.jar",
    f"net/minecraftforge/javafmllanguage/{FORGE_VERSION}/javafmllanguage-{FORGE_VERSION}.jar",
    "net/minecraftforge/eventbus/6.2.33/eventbus-6.2.33.jar",
    "net/minecraftforge/forgespi/7.0.1/forgespi-7.0.1.jar",
    "net/minecraftforge/coremods/5.2.4/coremods-5.2.4.jar",
    "net/minecraftforge/accesstransformers/8.0.4/accesstransformers-8.0.4.jar",
    "net/minecraftforge/mergetool/1.1.5/mergetool-1.1.5-api.jar",
    "net/minecraftforge/modlauncher/10.2.4/modlauncher-10.2.4.jar",
    "net/minecraftforge/securemodules/2.2.24/securemodules-2.2.24.jar",
    "net/minecraftforge/bootstrap/2.1.8/bootstrap-2.1.8.jar",
    "net/minecraftforge/bootstrap-api/2.1.8/bootstrap-api-2.1.8.jar",
    "net/minecraftforge/roimfs/1.0.0/roimfs-1.0.0.jar",
    "net/minecraftforge/unsafe/0.2.0/unsafe-0.2.0.jar",
    "net/minecraftforge/JarJarFileSystems/0.3.19/JarJarFileSystems-0.3.19.jar",
    "net/minecraftforge/JarJarMetadata/0.3.19/JarJarMetadata-0.3.19.jar",
    "net/minecraftforge/JarJarSelector/0.3.19/JarJarSelector-0.3.19.jar",
    "org/spongepowered/mixin/0.8.5/mixin-0.8.5.jar",
    "cpw/mods/securejarhandler/2.1.10/securejarhandler-2.1.10.jar",
    "cpw/mods/bootstraplauncher/1.1.2/bootstraplauncher-1.1.2.jar",
    "com/electronwill/night-config/core/3.6.4/core-3.6.4.jar",
    "com/electronwill/night-config/toml/3.6.4/toml-3.6.4.jar",
    "net/jodah/typetools/0.6.3/typetools-0.6.3.jar",
    "net/minecrell/terminalconsoleappender/1.2.0/terminalconsoleappender-1.2.0.jar",
    "org/openjdk/nashorn/nashorn-core/15.4/nashorn-core-15.4.jar",
    "org/antlr/antlr4-runtime/4.9.1/antlr4-runtime-4.9.1.jar",
    "org/apache/maven/maven-artifact/3.8.5/maven-artifact-3.8.5.jar",
    "com/mojang/logging/1.1.1/logging-1.1.1.jar",
    "com/mojang/brigadier/1.1.8/brigadier-1.1.8.jar",
    "com/mojang/datafixerupper/6.0.8/datafixerupper-6.0.8.jar",
    "com/google/guava/guava/31.1-jre/guava-31.1-jre.jar",
    "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar",
    "org/slf4j/slf4j-api/2.0.1/slf4j-api-2.0.1.jar",
    "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1.jar",
    "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar",
    "it/unimi/dsi/fastutil/8.5.9/fastutil-8.5.9.jar",
    "org/joml/joml/1.10.5/joml-1.10.5.jar",
    "io/netty/netty-buffer/4.1.82.Final/netty-buffer-4.1.82.Final.jar",
    "io/netty/netty-common/4.1.82.Final/netty-common-4.1.82.Final.jar",
    "io/netty/netty-handler/4.1.82.Final/netty-handler-4.1.82.Final.jar",
    "io/netty/netty-transport/4.1.82.Final/netty-transport-4.1.82.Final.jar",
    "io/netty/netty-resolver/4.1.82.Final/netty-resolver-4.1.82.Final.jar",
]


def log(message: str) -> None:
    print(f"[build] {message}", flush=True)


def run(command: list[str], **kwargs) -> None:
    log("$ " + " ".join(str(c) for c in command[:6]) + (" ..." if len(command) > 6 else ""))
    result = subprocess.run([str(c) for c in command], text=True, **kwargs)
    if result.returncode != 0:
        raise SystemExit(f"command failed with exit code {result.returncode}")


def properties() -> dict[str, str]:
    values = {}
    for line in (PROJ / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def asm_classpath() -> str:
    root = FORGE_GRADLE_CACHE / "modules-2" / "files-2.1" / "org.ow2.asm"
    jars = []
    for name in ("asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis"):
        found = sorted(root.glob(f"{name}/9.9.1/*/{name}-9.9.1.jar"))
        if not found:
            raise SystemExit(f"ASM jar not found for {name} under {root}")
        jars.append(str(found[0]))
    return os.pathsep.join(jars)


def ae2_jar() -> Path:
    version = properties().get("ae2_version", "15.4.10")
    jar = PROJ / "libs" / f"appliedenergistics2-forge-{version}.jar"
    if not jar.exists():
        raise SystemExit(f"AE2 jar missing: {jar}")
    return jar


def jei_jar() -> Path | None:
    """Optional compile time (and runtime) dependency: JEI, used for the ghost ingredient drag-in."""
    version = properties().get("jei_version", "")
    found = sorted((PROJ / "libs").glob("jei-*.jar"))
    if not found:
        return None
    if version:
        for jar in found:
            if version in jar.name:
                return jar
    return found[-1]


def tool_classpath() -> str:
    return os.pathsep.join([str(DEV / "out"), asm_classpath()])


# --------------------------------------------------------------------------- steps

def step_maps() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    if not TSRG.exists():
        with zipfile.ZipFile(MCP_CONFIG) as archive:
            TSRG.write_bytes(archive.read("config/joined.tsrg"))
        log(f"extracted {TSRG.name} ({TSRG.stat().st_size} bytes)")
    run([JAVA, "-Xmx3G", "-cp", tool_classpath(), "McRemap", "mkmap", PROGUARD, TSRG, MAP_DIR])


def step_mc() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    run([JAVA, "-Xmx4G", "-cp", tool_classpath(), "McRemap", "mc2official", SRG_JAR, MAP_DIR, MC_OFFICIAL])
    run([JAVA, "-Xmx4G", "-cp", tool_classpath(), "McRemap", "mc2official", FORGE_CLIENT_JAR, MAP_DIR,
         PATCHED_OFFICIAL, SRG_JAR])
    # extract Forge's access transformers from the userdev jar and apply them
    if not AT_CONFIG.exists():
        with zipfile.ZipFile(USERDEV_JAR) as archive:
            AT_CONFIG.write_bytes(archive.read("ats/accesstransformer.cfg"))
        log(f"extracted {AT_CONFIG.name} ({AT_CONFIG.stat().st_size} bytes)")
    run([JAVA, "-Xmx4G", "-cp", tool_classpath(), "McRemap", "applyat", MC_OFFICIAL, AT_CONFIG, MAP_DIR,
         MC_OFFICIAL_AT])
    run([JAVA, "-Xmx4G", "-cp", tool_classpath(), "McRemap", "applyat", PATCHED_OFFICIAL, AT_CONFIG, MAP_DIR,
         PATCHED_OFFICIAL_AT])


def compile_classpath() -> str:
    entries = [str(PATCHED_OFFICIAL_AT), str(MC_OFFICIAL_AT)]
    for rel in LIBRARY_JARS:
        jar = LIBS / rel
        if not jar.exists():
            raise SystemExit(f"library jar missing: {jar}")
        entries.append(str(jar))
    entries.append(str(ae2_jar()))
    jei = jei_jar()
    if jei is not None:
        entries.append(str(jei))
    return os.pathsep.join(entries)


def step_compile() -> None:
    if CLASSES.exists():
        shutil.rmtree(CLASSES)
    CLASSES.mkdir(parents=True, exist_ok=True)
    sources = sorted((PROJ / "src" / "main" / "java").rglob("*.java"))
    # Only the source list goes into an @argfile (javac reads argfiles with the platform charset, so
    # the classpath - which lives under a non-ASCII launcher path - is passed on the command line).
    argfile = OUT / "javac-sources.txt"
    argfile.write_text("\n".join(f'"{path.as_posix()}"' for path in sources), encoding="ascii")
    classpath = compile_classpath()
    log(f"compiling {len(sources)} sources against {len(classpath.split(os.pathsep))} classpath entries")
    result = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-nowarn", "-proc:none",
         "-d", str(CLASSES), "-cp", classpath, f"@{argfile}"],
        text=True, capture_output=True)
    if result.stdout:
        print(result.stdout[:20000])
    if result.returncode != 0:
        print(result.stderr[:20000])
        raise SystemExit(f"javac failed with exit code {result.returncode}")
    count = len(list(CLASSES.rglob("*.class")))
    log(f"compiled {count} class files")


def step_reobf() -> None:
    if REOBF.exists():
        shutil.rmtree(REOBF)
    extras = [str(PATCHED_OFFICIAL), str(ae2_jar())]
    jei = jei_jar()
    if jei is not None:
        extras.append(str(jei))
    run([JAVA, "-Xmx4G", "-cp", tool_classpath(), "McRemap", "reobf", CLASSES, MC_OFFICIAL, MAP_DIR, REOBF,
         *extras])


def step_package() -> None:
    props = properties()
    mod_id = props["mod_id"]
    version = props["mod_version"]
    tokens = {
        "loader_version_range": props["loader_version_range"],
        "mod_license": props["mod_license"],
        "mod_id": mod_id,
        "mod_version": version,
        "mod_name": props["mod_name"],
        "mod_description": props["mod_description"],
        "mod_authors": props["mod_authors"],
        "forge_version_range": props["forge_version_range"],
        "minecraft_version_range": props["minecraft_version_range"],
    }
    resources = PROJ / "src" / "main" / "resources"
    target = OUT / f"{mod_id}-{version}.jar"
    reobf_classes = len(list(REOBF.rglob("*.class")))
    if reobf_classes == 0:
        raise SystemExit("no reobfuscated classes - run the reobf step first")

    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as jar:
        write_entry(jar, "META-INF/MANIFEST.MF", "\r\n".join([
            "Manifest-Version: 1.0",
            f"Specification-Title: {mod_id}",
            f"Specification-Vendor: {props['mod_authors']}",
            "Specification-Version: 1",
            f"Implementation-Title: {mod_id}",
            f"Implementation-Version: {version}",
            f"Implementation-Vendor: {props['mod_authors']}",
            "",
            "",
        ]).encode("utf-8"))
        for path in sorted(REOBF.rglob("*.class")):
            write_entry(jar, path.relative_to(REOBF).as_posix(), path.read_bytes())
        for path in sorted(resources.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(resources).as_posix()
            if relative == "META-INF/MANIFEST.MF":
                continue
            if relative in ("META-INF/mods.toml", "pack.mcmeta"):
                text = path.read_text(encoding="utf-8")
                for key, value in tokens.items():
                    text = text.replace("${" + key + "}", value)
                write_entry(jar, relative, text.encode("utf-8"))
            else:
                write_entry(jar, relative, path.read_bytes())
    log(f"wrote {target} ({target.stat().st_size} bytes, {reobf_classes} classes)")


def write_entry(jar: zipfile.ZipFile, arcname: str, data: bytes) -> None:
    """Writes a zip entry with a fixed timestamp so the jar hash is reproducible."""
    info = zipfile.ZipInfo(arcname, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    jar.writestr(info, data)


def step_verify() -> None:
    props = properties()
    target = OUT / f"{props['mod_id']}-{props['mod_version']}.jar"
    references = [str(SRG_JAR), str(PATCHED_OFFICIAL), str(ae2_jar())]
    jei = jei_jar()
    if jei is not None:
        references.append(str(jei))
    for rel in LIBRARY_JARS:
        references.append(str(LIBS / rel))
    run([JAVA, "-Xmx4G", "-cp", tool_classpath(), "McRemap", "verify", target, SRG_JAR, *references[1:]])


STEPS = {
    "maps": step_maps,
    "mc": step_mc,
    "compile": step_compile,
    "reobf": step_reobf,
    "package": step_package,
    "verify": step_verify,
}
DEFAULT_ORDER = ["maps", "mc", "compile", "reobf", "package", "verify"]


def main() -> None:
    requested = sys.argv[1:] or ["all"]
    if "all" in requested:
        requested = DEFAULT_ORDER
    for name in requested:
        if name not in STEPS:
            raise SystemExit(f"unknown step {name}; known: all, {', '.join(STEPS)}")
        log(f"=== step {name} ===")
        STEPS[name]()


if __name__ == "__main__":
    main()
