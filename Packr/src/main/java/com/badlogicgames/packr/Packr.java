/*
 * Copyright 2020 See AUTHORS file
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
 *
 */

package com.badlogicgames.packr;

import com.lexicalscope.jewel.cli.ArgumentValidationException;
import com.lexicalscope.jewel.cli.CliFactory;
import com.lexicalscope.jewel.cli.ValidationFailure;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.utils.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static com.badlogicgames.packr.ArchiveUtils.extractArchive;

/**
 * Takes a couple of parameters and a JRE and bundles them into a platform specific distributable (zip on Windows and Linux, app bundle on Mac OS X).
 *
 * @author badlogic
 */
public class Packr {

   private PackrConfig config;
   private Predicate<File> removePlatformLibsFileFilter = f -> false;

   /**
    * The main CLI entrance.
    *
    * @param args Should conform to {@link PackrCommandLine}
    */
   public static void main(String[] args) {

      try {

         PackrCommandLine commandLine = CliFactory.parseArguments(PackrCommandLine.class, args.length > 0 ? args : new String[]{"-h"});

         if (commandLine.help()) {
            return;
         }

         new Packr().pack(new PackrConfig(commandLine));

      } catch (ArgumentValidationException argumentException) {
         for (ValidationFailure failure : argumentException.getValidationFailures()) {
            System.err.println(failure.getMessage());
         }
         System.exit(-1);
      } catch (IOException | CompressorException | ArchiveException exception) {
         exception.printStackTrace();
         System.exit(-1);
      }
   }

   /**
    * Reads a classpath resource and loads it into a byte array.
    *
    * @param resource the resource to load from the classpath relative to {@link Packr#getClass()}. Use a leading "/" to not load relative to the Packr
    *       package "com/badlogicgames/packr"
    *
    * @return the byte array containing the contents of the resource
    *
    * @throws IOException if an IO error occurs
    */
   private static byte[] readResource(String resource) throws IOException {
      try (InputStream inputStream = Packr.class.getResourceAsStream(resource)) {
         if (inputStream == null) {
            throw new IllegalArgumentException("Couldn't find resource " + resource + " relative to class " + Packr.class.getName());
         }
         return IOUtils.toByteArray(inputStream);
      }
   }

   /**
    * Loads a resource relative to this package and replaces the keys in {@code value} with their values.
    *
    * @param resource the resource to load from the classpath
    * @param values the values to replace
    *
    * @return the resource content loaded and replaces with {@code values}
    *
    * @throws IOException if an IO error occurs
    */
   private static String readResourceAsString(@SuppressWarnings("SameParameterValue") String resource, Map<String, String> values) throws IOException {
      return replace(new String(readResource(resource), StandardCharsets.UTF_8), values);
   }

   /**
    * Replaces every occurrence of {@code values} key with it's value in the map.
    *
    * @param txt the text to replace values in
    * @param values the mapping of values to replace
    *
    * @return a new String with all the keys in {@code values} replaced with their map value
    */
   private static String replace(String txt, Map<String, String> values) {
      for (String key : values.keySet()) {
         String value = values.get(key);
         txt = txt.replace(key, value);
      }
      return txt;
   }

   /**
    * Install application-side file filter to specify which (additional) files can be deleted during the removePlatformLibs phase.
    * <p>
    * This filter is checked first, before evaluating the "--removelibs" and "--libs" options.
    *
    * @param filter file filter for removing libraries
    *
    * @return true if file should be removed (deleted)
    */
   @SuppressWarnings("unused") public Packr setRemovePlatformLibsFileFilter(Predicate<File> filter) {
      removePlatformLibsFileFilter = filter;
      return this;
   }

   /**
    * Process all inputs from {@code config} and create an output bundle in {@link PackrConfig#outDir}.
    *
    * @param config the configuration information for creating an executable and asset bundle
    *
    * @throws IOException if an IO error occurs
    * @throws CompressorException if a compression error occurs
    * @throws ArchiveException if an archive error occurs
    */
   @SuppressWarnings("WeakerAccess") public void pack(PackrConfig config) throws IOException, CompressorException, ArchiveException {

      config.validate();
      this.config = config;

      PackrOutput output = new PackrOutput(config.outDir, config.outDir);

      cleanAndCreateOutputFolder(output);

      output = buildMacBundle(output);

      copyExecutableAndClasspath(output);

      writeConfig(output);

      copyAndMinimizeJRE(output, config);

      copyResources(output);

      PackrReduce.removePlatformLibs(output, config, removePlatformLibsFileFilter);

      System.out.println("Done!");
   }

   /**
    * If the output directory already exists, delete it. Then create the directory.
    *
    * @param output Deletes if it exists and then creates {@link PackrOutput#executableFolder}
    *
    * @throws IOException if an IO error occurs
    */
   private void cleanAndCreateOutputFolder(PackrOutput output) throws IOException {
      File folder = output.executableFolder;
      if (folder.exists()) {
         System.out.println("Cleaning output directory '" + folder.getAbsolutePath() + "' ...");
         PackrFileUtils.deleteDirectory(folder);
      }
      Files.createDirectories(folder.toPath());
   }

   /**
    * Create a bundle for the macOS platform.
    *
    * @param output the output location for the bundle
    *
    * @return the output paths for the bundle
    *
    * @throws IOException if an IO error occurs
    */
   private PackrOutput buildMacBundle(PackrOutput output) throws IOException {
      if (config.platform != PackrConfig.Platform.MacOS) {
         return output;
      }

      // replacement strings for Info.plist
      Map<String, String> values = new HashMap<>();

      values.put("${executable}", config.executable);

      if (config.bundleIdentifier != null) {
         values.put("${bundleIdentifier}", config.bundleIdentifier);
      } else {
         values.put("${bundleIdentifier}", config.mainClass.substring(0, config.mainClass.lastIndexOf('.')));
      }

      // create folder structure

      File root = output.executableFolder;

      Files.createDirectories(root.toPath().resolve("Contents"));
      try (FileWriter info = new FileWriter(new File(root, "Contents/Info.plist"))) {
         String plist = readResourceAsString("/Info.plist", values);
         info.write(plist);
      }

      File target = new File(root, "Contents/MacOS");
      Files.createDirectories(target.toPath());

      File resources = new File(root, "Contents/Resources");
      Files.createDirectories(resources.toPath());

      if (config.iconResource != null) {
         // copy icon to Contents/Resources/icons.icns
         if (config.iconResource.exists()) {
            Files.copy(config.iconResource.toPath(), resources.toPath().resolve("icons.icns"), StandardCopyOption.COPY_ATTRIBUTES);
         }
      }

      return new PackrOutput(target, resources);
   }

   /**
    * Copy the packr launcher executable and classpath files into the bundle.
    *
    * @param output the directory to copy the executable and classpath entries into
    *
    * @throws IOException if an IO error occurs
    */
   private void copyExecutableAndClasspath(PackrOutput output) throws IOException {
      byte[] exe = null;
      String extension = "";

      switch (config.platform) {
         case Windows64:
            exe = readResource("/packr-windows-x64.exe");
            extension = ".exe";
            break;
         case Linux64:
            exe = readResource("/packr-linux-x64");
            break;
         case MacOS:
            exe = readResource("/packr-mac");
            break;
      }

      System.out.println("Copying executable ...");
      Files.write(output.executableFolder.toPath().resolve(config.executable + extension), exe);

      PackrFileUtils.chmodX(new File(output.executableFolder, config.executable + extension));

      System.out.println("Copying classpath(s) ...");
      for (String file : config.classpath) {
         File cpSrc = new File(file);
         File cpDst = new File(output.resourcesFolder, new File(file).getName());

         if (cpSrc.isFile()) {
            Files.copy(cpSrc.toPath(), cpDst.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
         } else if (cpSrc.isDirectory()) {
            PackrFileUtils.copyDirectory(cpSrc, cpDst);
         } else {
            System.err.println("Warning! Classpath not found: " + cpSrc);
         }
      }
   }

   /**
    * Writes a configuration file for the Packr launcher.
    *
    * @param output the location to write the configuration file
    *
    * @throws IOException if an IO error occurs
    */
   private void writeConfig(PackrOutput output) throws IOException {
      StringBuilder builder = new StringBuilder();
      builder.append("{\n");
      builder.append("  \"classPath\": [");

      String delimiter = "\n";
      for (String f : config.classpath) {
         builder.append(delimiter).append("    \"").append(new File(f).getName()).append("\"");
         delimiter = ",\n";
      }
      builder.append("\n  ],\n");

      builder.append("  \"mainClass\": \"").append(config.mainClass).append("\",\n");
      builder.append("  \"vmArgs\": [\n");

      for (int i = 0; i < config.vmArgs.size(); i++) {
         String vmArg = config.vmArgs.get(i);
         builder.append("    \"");
         if (!vmArg.startsWith("-")) {
            builder.append("-");
         }
         builder.append(vmArg).append("\"");
         if (i < config.vmArgs.size() - 1) {
            builder.append(",");
         }
         builder.append("\n");
      }
      builder.append("  ]\n");
      builder.append("}");

      try (Writer writer = new FileWriter(new File(output.resourcesFolder, "config.json"))) {
         writer.write(builder.toString());
      }
   }

   /**
    * Acquires the JDK specified and unpacks it if it's not a directory into a new temporary directory. The new temporary directory for the JDK is minimized.
    *
    * @param output the output for the minimized JDK
    * @param config the packr config for locating the JDK
    *
    * @throws IOException if an IO error occurs
    * @throws CompressorException if a compression error occurs
    * @throws ArchiveException if an archive error occurs
    */
   private void copyAndMinimizeJRE(PackrOutput output, PackrConfig config) throws IOException, CompressorException, ArchiveException {
      boolean extractToCache = config.cacheJre != null;
      boolean skipExtractToCache = false;

      // check if JRE extraction (and minimize) can be skipped
      if (extractToCache && config.cacheJre.exists()) {
         if (config.cacheJre.isDirectory()) {
            // check if the cache directory is empty
            String[] files = config.cacheJre.list();
            skipExtractToCache = files != null && files.length > 0;
         } else {
            throw new IOException(config.cacheJre + " must be a directory");
         }
      }

      // path to extract JRE to (cache, or target folder)
      File jreStoragePath = extractToCache ? config.cacheJre : output.resourcesFolder;

      if (skipExtractToCache) {
         System.out.println("Using cached JRE in '" + config.cacheJre + "' ...");
      } else {
         // path to extract JRE from (folder, zip or remote)
         boolean fetchFromRemote = config.jdk.startsWith("http://") || config.jdk.startsWith("https://");
         File jdkFile = fetchFromRemote ? new File(jreStoragePath, "jdk.zip") : new File(config.jdk);

         // download from remote
         if (fetchFromRemote) {
            System.out.println("Downloading JDK from '" + config.jdk + "' ...");
            try (InputStream remote = new URL(config.jdk).openStream()) {
               try (OutputStream outJdk = new FileOutputStream(jdkFile)) {
                  IOUtils.copy(remote, outJdk);
               }
            }
         }

         // unpack JDK zip (or copy if it's a folder)
         System.out.println("Unpacking JRE ...");
         File tmp = new File(jreStoragePath, "tmp");
         if (tmp.exists()) {
            PackrFileUtils.deleteDirectory(tmp);
         }
         Files.createDirectories(tmp.toPath());

         if (jdkFile.isDirectory()) {
            PackrFileUtils.copyDirectory(jdkFile, tmp);
         } else {
            extractArchive(jdkFile.toPath(), tmp.toPath());
         }

         // copy the JRE sub folder
         File jre = searchJre(tmp);
         if (jre == null) {
            throw new IOException("Couldn't find JRE in JDK, see '" + tmp.getAbsolutePath() + "'");
         }

         PackrFileUtils.copyDirectory(jre, new File(jreStoragePath, "jre"));
         PackrFileUtils.deleteDirectory(tmp);

         if (fetchFromRemote) {
            Files.deleteIfExists(jdkFile.toPath());
         }

         // run minimize
         PackrReduce.minimizeJre(jreStoragePath, config);
      }

      if (extractToCache) {
         // if cache is used, copy again here; if the JRE is cached already,
         // this is the only copy done (and everything above is skipped)
         PackrFileUtils.copyDirectory(jreStoragePath, output.resourcesFolder);
      }
   }

   /**
    * Searches the directory {@code tmp}, if it's name is "jre" and it contains bin/java[.exe] then that it is returned. Otherwise it searches all it's
    * sub-directories for a matching "jar" directory.
    *
    * @param tmp the directory to search for a directory named "jre" that contains "bin/java[.exe]"
    *
    * @return tmp or a directory in tmp that is named "jre" and contains "bin/java[.exe]"
    */
   private File searchJre(File tmp) {
      if (tmp.getName().equals("jre") && tmp.isDirectory()
              && (new File(tmp, "bin/java").exists() || new File(tmp, "bin/java.exe").exists())) {
         return tmp;
      }

      File[] children = tmp.listFiles();
      if (children != null) {
         for (File child : children) {
            if (child.isDirectory()) {
               File found = searchJre(child);
               if (found != null) {
                  return found;
               }
            }
         }
      }

      return searchJre9(tmp);
   }

   private File searchJre9(File tmp) {
      if (tmp.isDirectory()
              && (new File(tmp, "bin/java").exists() || new File(tmp, "bin/java.exe").exists())) {
         return tmp;
      }

      File[] childs = tmp.listFiles();
      if (childs != null) {
         for (File child : childs) {
            if (child.isDirectory()) {
               File found = searchJre9(child);
               if (found != null) {
                  return found;
               }
            }
         }
      }

      return null;
   }

   /**
    * Copies the specified bundle resources into the bundle.
    *
    * @param output the resource output folder to copy into
    *
    * @throws IOException if an IO error occurs
    */
   private void copyResources(PackrOutput output) throws IOException {
      if (config.resources != null) {
         System.out.println("Copying resources ...");

         for (File file : config.resources) {
            if (!file.exists()) {
               throw new IOException("Resource '" + file.getAbsolutePath() + "' doesn't exist");
            }

            if (file.isFile()) {
               Files.copy(file.toPath(), output.resourcesFolder.toPath().resolve(file.getName()), StandardCopyOption.COPY_ATTRIBUTES);
            }

            if (file.isDirectory()) {
               File target = new File(output.resourcesFolder, file.getName());
               Files.createDirectories(target.toPath());
               PackrFileUtils.copyDirectory(file, target);
            }
         }
      }
   }

}
