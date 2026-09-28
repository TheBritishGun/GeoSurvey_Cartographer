#!/usr/bin/env python3
"""Builds OpenMap Collect without Gradle.

Usage:  python build.py [--jdk <path>] [--clean] [--build-dir <path>]
"""
import argparse, importlib.util, json, os, pathlib, shutil, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
LIBS = os.path.join(ROOT, "libs")
SRC_MAIN = os.path.join(ROOT, "src", "main", "java")
RES_MAIN = os.path.join(ROOT, "src", "main", "resources")
# The collector's resources directory.
RES_COLLECTOR = os.path.join(ROOT, "src", "main", "resources")
BUILD = os.path.join(ROOT, "build")

# The sandpaper tree, beside this one.
SANDPAPER = os.path.join(os.path.dirname(ROOT), "sandpaper")
NESTED_DIR = "META-INF/jars"

# Paths the collector build must not contain.
COLLECTOR_FORBIDDEN = ("dev/openmap/client/gui/", "dev/openmap/plate/")


def props(path):
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def version_key(name):
    """Sort key that orders version numbers numerically, not as text."""
    parts, digits = [], ""
    for ch in name:
        if ch in "0123456789":
            digits += ch
        elif digits:
            parts.append(int(digits))
            digits = ""
    if digits:
        parts.append(int(digits))
    return (parts, name)


def find_jdk(explicit):
    for cand in filter(None, [explicit, os.environ.get("JAVA_HOME")]):
        if os.path.exists(os.path.join(cand, "bin", "javac.exe")) or \
           os.path.exists(os.path.join(cand, "bin", "javac")):
            return cand
    tools = os.path.join(os.path.expanduser("~"), "tools")
    if os.path.isdir(tools):
        names = [n for n in os.listdir(tools) if n.startswith("jdk-25")]
        if names:
            return os.path.join(tools, max(names, key=version_key))
    sys.exit("No JDK 25 found. Pass --jdk <path> or set JAVA_HOME.")


def tool(jdk, name):
    exe = os.path.join(jdk, "bin", name + ".exe")
    return exe if os.path.exists(exe) else os.path.join(jdk, "bin", name)


def sources(root):
    return [os.path.join(d, f).replace("\\", "/")
            for d, _, fs in os.walk(root) for f in fs if f.endswith(".java")]


def sandpaper_tools(root=None):
    """Loads sandpaper's tools/source_id.py by path."""
    path = os.path.join(root or SANDPAPER, "tools", "source_id.py")
    if not os.path.exists(path):
        sys.exit("Sandpaper tool missing: %s\n"
                 "  Check that the sandpaper tree sits beside this one." % path)
    spec = importlib.util.spec_from_file_location("sandpaper_source_id", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def refuse_stray_sandpaper(libs):
    """Refuse a Sandpaper jar left in libs/."""
    strays = [f for f in sorted(os.listdir(libs))
              if f.startswith("sandpaper-") and f.endswith(".jar")]
    if strays:
        sys.exit("Sandpaper jars in %s:\n" % libs
                 + "".join("  %s\n" % s for s in strays)
                 + "  Delete these files. Correct jar: %s."
                   % os.path.join(SANDPAPER, "build"))


def nested_jar(root=None):
    """Finds the Sandpaper jar to nest and checks it matches the sources."""
    root = root or SANDPAPER
    sp = sandpaper_tools(root)
    name = sp.jar_name(root)
    path = os.path.join(root, "build", name)
    if not os.path.exists(path):
        sys.exit("nested dependency missing: %s\n"
                 "  fabric.mod.json declares %s/%s.\n"
                 "  Build it first:  cd %s && python build.py --clean"
                 % (path, NESTED_DIR, name, root))

    # Compared by content, not by timestamp.
    was = sp.recorded(path)
    if was is None:
        sys.exit("nested dependency is not identified: %s\n"
                 "  Jar carries no %s.\n"
                 "  Rebuild it:  cd %s && python build.py --clean"
                 % (path, sp.RECORD, root))
    live = sp.source_id(root)
    if was.get("source_sha256") != live:
        sys.exit("nested dependency is stale: %s\n"
                 "  built from : %s\n"
                 "  sources now: %s\n"
                 "  mismatch with %s.\n"
                 "  Rebuild it:  cd %s && python build.py --clean"
                 % (path, was.get("source_sha256", "?"), live,
                    os.path.join(root, "src", "main"), root))
    return name, path, was


def mixinextras_jar(build):
    loader = os.path.join(LIBS, "fabric-loader-0.19.3.jar")
    entry = "META-INF/jars/mixinextras-fabric-0.5.4.jar"
    if not os.path.isfile(loader):
        sys.exit("Fabric loader jar missing: %s" % loader)
    try:
        with zipfile.ZipFile(loader) as archive:
            contents = archive.read(entry)
    except KeyError:
        sys.exit("MixinExtras nested jar missing from %s: %s" % (loader, entry))
    except zipfile.BadZipFile:
        sys.exit("Fabric loader jar is not readable: %s" % loader)
    path = os.path.join(build, "mixinextras-fabric-0.5.4.jar")
    with open(path, "wb") as output:
        output.write(contents)
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jdk")
    ap.add_argument("--clean", action="store_true")
    ap.add_argument("--build-dir", help="write classes, resources and the jar here "
                                        "instead of build/")
    ap.add_argument("--publish", action="store_true",
                    help="after building, copy this jar to the AIO Mods folders "
                         "and the Modrinth profile, archiving what it replaces, "
                         "then refuse if any published jar is stale")
    ap.add_argument("--target", choices=("collector",), default="collector",
                    help="accepts only collector, the sole target this tree builds")
    args = ap.parse_args()

    # Only target: collector.
    collector = True

    jdk = find_jdk(args.jdk)
    p = props(os.path.join(ROOT, "gradle.properties"))
    mod_id = p.get("mod_id", "openmap-collect")
    version = f"{p.get('mod_version','0.0.0')}+{p.get('minecraft_version','unknown')}"
    res_dir = RES_COLLECTOR if collector else RES_MAIN

    # Resolve the nested jar before compiling.
    nested_name, nested_path, nested_id = nested_jar()
    nested_entry = f"{NESTED_DIR}/{nested_name}"
    # Check libs/ before clearing the build output.
    refuse_stray_sandpaper(LIBS)

    build = os.path.abspath(args.build_dir) if args.build_dir else BUILD

    # Per-target output directories.
    classes = os.path.join(build, "classes", args.target)
    resources = os.path.join(build, "resources", args.target)

    if args.clean and os.path.isdir(build):
        shutil.rmtree(build)

    # Clear classes and resources every run.
    for d in (classes, resources):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d, exist_ok=True)

    mixinextras_path = mixinextras_jar(build)

    jars = [os.path.join(LIBS, f).replace("\\", "/")
            for f in sorted(os.listdir(LIBS)) if f.endswith(".jar")]
    if not jars:
        sys.exit(f"No jars in {LIBS}. Run: python tools/fetch_deps.py.")

    # Add the nested and mixinextras jars to the classpath.
    jars.append(nested_path.replace("\\", "/"))
    jars.append(mixinextras_path.replace("\\", "/"))
    cp = ";".join(jars) if os.name == "nt" else ":".join(jars)

    # Every source in the tree.
    srcs = sources(SRC_MAIN)

    argfile = os.path.join(build, "javac.args")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("--release 25\n")
        f.write('-d "%s"\n' % classes.replace("\\", "/"))
        f.write('-cp "%s"\n' % cp)
        f.write("-Xlint:all\n")
        if collector:
            # Emit classes javac reaches implicitly.
            f.write('-sourcepath "%s"\n' % SRC_MAIN.replace("\\", "/"))
            f.write("-implicit:class\n")
        for s in srcs:
            f.write('"%s"\n' % s)

    print(f"target   : {args.target}  (id {mod_id})")
    print(f"jdk      : {jdk}")
    print(f"sources  : {len(srcs)}" + (""
                                       if collector else ""))
    print(f"classpath: {len(jars)} jars")
    r = subprocess.run([tool(jdk, "javac"), "@" + argfile.replace("\\", "/")])
    if r.returncode != 0:
        sys.exit("compile failed")
    print("compile  : OK")


    subs = {
        "version": version,
        "minecraft_version": p.get("minecraft_version", ""),
        "loader_version": p.get("loader_version", ""),
        "sandpaper_jar": nested_name,
        # The nested jar's own recorded version.
        "sandpaper_version": nested_id["mod_version"],
    }
    declared = []
    for d, _, fs in os.walk(res_dir):
        for fn in fs:
            src = os.path.join(d, fn)
            rel = os.path.relpath(src, res_dir)
            dst = os.path.join(resources, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if fn == "fabric.mod.json":
                text = open(src, encoding="utf-8").read()
                for k, v in subs.items():
                    text = text.replace("${%s}" % k, v)
                if "${" in text:
                    sys.exit("unsubstituted placeholder in %s: %s"
                             % (rel, text[text.index("${"):][:40]))
                declared = [j["file"] for j in json.loads(text).get("jars", [])]
                open(dst, "w", encoding="utf-8").write(text)
            else:
                shutil.copy2(src, dst)

    # The manifest's declared jars must match what is stored.
    if declared != [nested_entry]:
        sys.exit("fabric.mod.json declares nested jars %s, build stores %s"
                 % (declared, [nested_entry]))

    # Check for a stray jar under META-INF/jars/.
    for base in (classes, resources):
        stray = os.path.join(base, *NESTED_DIR.split("/"))
        if os.path.exists(stray):
            sys.exit("%s exists.\n"
                     "  Delete it or rebuild with --clean. Correct jar: %s."
                     % (stray, os.path.join(SANDPAPER, "build")))

    out_jar = os.path.join(build, f"{p.get('jar_name', mod_id)}-{version}.jar")


    OURS = ("dev/openmap/",)

    # Check the compiled classes for forbidden paths.
    if collector:
        smuggled = sorted(
            os.path.relpath(os.path.join(d, fn), classes).replace("\\", "/")
            for d, _, fs in os.walk(classes) for fn in fs
            if os.path.relpath(os.path.join(d, fn), classes)
            .replace("\\", "/").startswith(COLLECTOR_FORBIDDEN))
        if smuggled:
            sys.exit("the collector closure has widened into the map UI:\n"
                     + "".join("  %s\n" % s for s in smuggled[:20])
                     + "  Find the reference and break it. Do not relax\n"
                       "  COLLECTOR_FORBIDDEN to make this pass.")

    skipped = 0
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for base, ours_only in ((classes, True), (resources, False)):
            for d, _, fs in os.walk(base):
                for fn in fs:
                    full = os.path.join(d, fn)
                    rel = os.path.relpath(full, base).replace("\\", "/")
                    if ours_only and not rel.startswith(OURS):
                        skipped += 1
                        continue
                    z.write(full, rel)
        # Write the nested jar from Sandpaper's build dir.
        z.write(nested_path, nested_entry)
        # The license the manifest declares.
        licence = os.path.join(ROOT, "LICENSE")
        if not os.path.isfile(licence):
            sys.exit("LICENSE is missing from the repository root."
                     " Manifests declare AGPL-3.0-or-later.")
        z.write(licence, "LICENSE")
    if skipped:
        print(f"skipped  : {skipped} classes from the classpath, not ours")
    print(f"nested   : {nested_entry}  ({round(os.path.getsize(nested_path)/1024,1)} KB)")
    # The sandpaper revision this jar compiled against and ships.
    print(f"sandpaper: {nested_id['mod_version']}  {nested_id['source_sha256']}")
    print(f"jar      : {out_jar}  ({round(os.path.getsize(out_jar)/1024,1)} KB)")

    if args.publish:
        publish(out_jar)


def publish(out_jar):
    """Delivers the jar just built, then refuses if anything published is still stale."""
    import datetime
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import publish as delivery  # noqa: E402

    stamp = datetime.datetime.now().strftime("%Y-%m-%d-%H%M")
    print("publish  :")
    delivery.deliver(pathlib.Path(out_jar), stamp,
                     log=lambda line: print(f"  {line}"))
    stale = delivery.audit(log=lambda line: print(f"  {line}"))
    if stale:
        print(f"{stale} published jar(s) carry a sandpaper the tree has moved"
              " past.")
        print("  Build the ones named above and publish them too.")
        sys.exit(1)


if __name__ == "__main__":
    main()
