# About
Packages your JAR, assets and a JVM for distribution on Windows, Linux and Mac OS X, adding a native executable file to make it appear like a native app. Packr is most suitable for GUI applications, such as games made with [libGDX](http://libgdx.badlogicgames.com/).

# Download
The latest build is available for [download here](https://github.com/karlsabo/packr/releases).

# Usage
You point packr at your JAR file(s) containing your code and assets, some configuration parameters, and a URL or local file location to a JDK build for your target platform.

Invoking packr from the command line may look like this:

```bash
java -jar packr.jar \
     --platform mac \
     --jdk OpenJDK8U-jdk_x64_mac_hotspot_8u252b09.tar.gz \
     --executable myapp \
     --classpath myapp.jar \
     --removelibs myapp.jar \
     --mainclass com.my.app.MainClass \
     --vmargs Xmx1G \
     --resources src/main/resources path/to/other/assets \
     --minimizejre soft \
     --output out-mac
```

See [PackrAllTestApp/packrAllTestApp.gradle.kts](https://github.com/karlsabo/packr/blob/master/PackrAllTestApp/packrAllTestApp.gradle.kts) for a concrete example of a Gradle script which bundles an application.

| Parameter | Meaning |
| --- | --- |
| platform | one of "windows64",  "linux64", "mac" |
| jdk | Directory, Zip file, tar.gz file, or URL to an archive file of an OpenJDK 8 or Oracle JDK 8 build containing a JRE. Adopt OpenJDK 8 is tested against <https://adoptopenjdk.net/releases.html>. You can also specify a directory to an unpacked JDK distribution. E.g. using ${java.home} in a build script|
| executable | name of the native executable, without extension such as ".exe" |
| classpath | file locations of the JAR files to package |
| removelibs (optional) | file locations of JAR files to remove native libraries which do not match the target platform. See below for details. |
| mainclass | the fully qualified name of the main class, using dots to delimit package names |
| vmargs | list of arguments for the JVM, without leading dashes, e.g. "Xmx1G" |
| resources (optional) | list of files and directories to be packaged next to the native executable |
| minimizejre | minimize the JRE by removing directories and files as specified by an additional config file. Comes with a few config files out of the box. See below for details on the minimization config file. |
| output | the output directory |
| cachejre (optional) | An optional directory to cache the result of JRE extraction and minimization. See below for details. |
| icon (optional, OS X) | location of an AppBundle icon resource (.icns file) |
| bundle (optional, OS X) | the bundle identifier of your Java application, e.g. "com.my.app" |
| verbose | prints more status information during processing, which can be useful for debugging |
| help | shows the command line interface help |

Alternatively, you can put all the command line arguments into a JSON file which might look like this:

```json
{
    "platform": "mac",
    "jdk": "/Users/badlogic/Downloads/OpenJDK8U-jdk_x64_mac_hotspot_8u252b09.tar.gz",
    "executable": "myapp",
    "classpath": [
        "myapp.jar"
    ],
    "removelibs": [
        "myapp.jar"
    ],
    "mainclass": "com.my.app.MainClass",
    "vmargs": [
       "Xmx1G"
    ],
    "resources": [
        "src/main/resources",
        "path/to/other/assets"
    ],
    "minimizejre": "soft",
    "output": "out-mac"
}
```

You can then invoke the tool like this:

```bash
java -jar packr-all.jar my-packr-config.json
```

It is possible to combine a JSON configuration and the command line. For single options, the command line parameter overrides the equivalent JSON option. For multi-options (e.g. `classpath` or `vmargs`), the options are merged.

This is an example which overrides the output folder and adds another VM argument. Note that the config file name is delimited by `--` because the option prior to it, `--vmargs`, allows multiple arguments:

```bash
java -jar packr-all.jar --output target/out-mac --vmargs Xms256m -- my-packr-config.json
```

Finally, you can use packr from within your Java code. Just add the JAR file to your project, either manually, or via the following Gradle dependency:

```Kotlin
repositories {
   mavenCentral()
}
dependencies {
   imlementation("com.nimblygames.packr:packr:2.3.0")
}
```

To invoke packr, you need to create an instance of `PackrConfig` and pass it to `Packr.pack()`:

```java
PackrConfig config = new PackrConfig();
config.platform = PackrConfig.Platform.Windows32;
config.jdk = "/User/badlogic/Downloads/openjdk-for-mac.zip";
config.executable = "myapp";
config.classpath = Arrays.asList("myjar.jar");
config.removePlatformLibs = config.classpath;
config.mainClass = "com.my.app.MainClass";
config.vmArgs = Arrays.asList("Xmx1G");
config.minimizeJre = "soft";
config.outDir = new java.io.File("out-mac");

new Packr().pack(config);
```

# Minimization

## JRE

A standard OpenJDK JRE weighs about 90 mb unpacked. Packr helps you cut down on that size, thus also reducing the download size of your app.

To minimize the JRE that is bundled with your app, you have to specify a minimization configuration file via the `minimizejre` flag you supply to Packr. A minimization configuration is a JSON file containing paths to files and directories within the JRE to be removed.

As an example, have a look at the `soft` profile configuration:

```json
{
  "reduce": [
    {
      "archive": "jre/lib/rt.jar",
      "paths": [
        "com/sun/corba",
        "com/sun/jndi",
        "com/sun/media",
        "com/sun/naming",
        "com/sun/rowset",
        "sun/applet",
        "sun/corba",
        "sun/management"
      ]
    }
  ],
  "remove": [
    {
      "platform": "*",
      "paths": [
        "jre/lib/rhino.jar"
      ]
    },
    {
      "platform": "windows",
      "paths": [
        "jre/bin/*.exe",
        "jre/bin/client"
      ]
    }
  ]
}
```

This configuration will unpack `rt.jar`, remove all the listed packages and classes in `com.sun.*` and `sun.*`, then repack `rt.jar` again. By default, the JRE uses zero-compression on its JAR files to make application startup a little faster, so this step will reduce the size of `rt.jar` substantially.

Then, rhino.jar (about 1.1MB) and, in case of a Windows JRE, all executable files in `jre/bin/` and the folder `jre/bin/client/` will be removed.

Packr comes with two such configurations out of the box, [`soft`](https://github.com/karlsabo/packr/blob/master/Packr/src/main/resources/minimize/soft) and [`hard`](https://github.com/karlsabo/packr/blob/master/Packr/src/main/resources/minimize/hard). The `hard` profile removes a few more files, and repacks some additional JAR files.

## The "removelibs" option
Minimization aside, packr can remove all dynamic libraries which do not match the target platform from your project JAR file(s):

| platform | files removed |
| --- | --- |
| Windows | `*.dylib`, `*.so` |
| Linux | `*.dll`, `*.dylib` |
| MacOS | `*.dll`, `*.so` |

This step is optional. If you don't need it, just remove the configuration parameter to speed up packr.

# Caching
Extracting and minimizing a JRE can take quite some time. If the `cachejre` option is used, the result of these operations is cached in the given folder, and can be reused in subsequent runs of packr.

As of now, packr doesn't do any elaborate checks to validate the content of this cache folder. So if you update the JDK, or change the minimize profile, you need to empty or remove this folder manually to force a change.

# Output
##Windows
When packing for Windows, the following folder structure will be generated
```
outdir/
   executable.exe
   yourjar.jar
   config.json
   jre/
```

##Linux
```
outdir/
   executable
   yourjar.jar
   config.json
   jre/
```

##Mac OS X
```
outdir/
   Contents/
      Info.plist
      MacOS/
         executable
      Resources/
         yourjar.jar
         config.json
         jre/
         icons.icns [if config.icon is set]
```

You can further modify the Info.plist to your liking, e.g. add icons, a bundle identifier etc. If your `output` folder has the `.app` extension it will be treated as an application bundle by Mac OS X.

# Executable command line interface
By default, the native executables forward any command line parameters to your Java application's main() function. So, with the configurations above, `./myapp -x y.z` is passed as `com.my.app.MainClass.main(new String[] {"-x", "y.z" })`.

The executables themselves expose an own interface, which has to be enabled explicitly by passing `-c` or `--cli` as the **very first** parameter. In this case, the special delimiter parameter `--` is used to separate the native CLI from parameters to be passed to Java. In this case, the example above would be equal to `./myapp -c [arguments] -- -x y.z`.

Try `./myapp -c --help` for a list of available options.

> Note: On Windows, the executable does not show any output by default. Here you can use `myapp.exe -c --console [arguments]` to spawn a console window, making terminal output visible.

# Building from source code
If you want to modify the code invoke Gradle.

    $ ./gradlew clean assemble

This will create a `packr-VERSION-all.jar` file in `Packr/build/libs` directory, you may invoke as described in the Usage section above.

## Gradle project structure
The Gradle build is set up as a multi-project build. In order to fully build the multi-project you must have a compatible JRE (Java 8) and C/C++ build tools that the Gradle build can find.
 
### DrOpt Gradle sub-project
This is a downloaded and unzipped <https://github.com/jamesderlin/dropt/releases> version 1.1.1 source code with a Gradle script used to build it for consumption by the PackrLauncher Gradle project. The DrOpt source required a few modifications to get it compiling, namely some explicit casting in the C code.

### Packr Gradle sub-project
This is the Java code for creating application bundles that can use the native launcher executables. This project also builds the packr-all uber/shadow jar that works as an executable jar.

### PackrLauncher Gradle sub-project
This contains the platform native code for loading the JVM and starting the packr bundled application.

### PackrAllTestApp Gradle sub-project
This is an example Hello world style application that bundles itself using packr and is used as a high level test suite to help reduce breaking changes.

## Limitations
* Only JDK 8 is supported (older JDKs probably work)
* Icons aren't set yet on Windows and Linux, you need to do that manually.
* Minimum platform requirement on MacOS is OS X 10.10 (Honestly only 10.15 macOS Catalina is tested).
* JRE minimization is very conservative. Depending on your app, you can carve out stuff from a JRE yourself, disable minimization and pass your custom JRE to packr.
* On MacOS, the JVM is spawned in its own thread by default, which is a requirement of AWT. This does not work with code based on LWJGL3/GLFW, which needs the JVM be spawned on the main thread. You can enforce the latter with adding the `-XstartOnFirstThread` VM argument to your MacOS packr config.

# License & Contributions
The code is licensed under the [Apache 2 license](http://www.apache.org/licenses/LICENSE-2.0.html). By contributing to this repository, you automatically agree that your contribution can be distributed under the Apache 2 license by the author of this project. You will not be able to revoke this right once your contribution has been merged into this repository.

# Security
Distributing a bundled JVM has security implications, just like bundling any other runtimes like Mono, Air, etc. Make sure you understand the implications before deciding to use this tool. Here's a [discussion on the topic](http://www.reddit.com/r/gamedev/comments/24orpg/packr_package_your_libgdxjavascalajvm_appgame_for/ch99zk2).
