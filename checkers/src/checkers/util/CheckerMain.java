package checkers.util;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * This class functions essentially the same as the JSR308 javac script EXCEPT that it adds the appropriate jdk.jar
 * to the bootclasspath and adds checkers.jar to the classpath passed to javac
 */
public class CheckerMain {

    /**
     * The paths searched for Checker Framework jars
     * TODO: Should probably remove this and put the binaries in ONE directory
     */
    public static List<String> SEARCH_PATHS = Arrays.asList("binary", ".");

    /**
     * Most logic of the CheckerMain main method is delegated to the CheckerMain class.  This method
     * just determines the relevant parameters to CheckerMain then tells it to invoke the JSR308
     * Type Annotations Compiler
     * @param args Command line arguments, eventually passed to the jsr308 type annotations compiler
     * @throws Exception Any exception thrown by the Checker Framework escape to the command line
     */
    public static void main(String[] args)  {
        final String pathToThisJar     = findPathJar(CheckerMain.class);
        final CheckerMain program      = new CheckerMain(pathToThisJar, SEARCH_PATHS, args);
        program.invokeCompiler();
    }

    /**
     * The path to the annotated jdk jar to use
     */
    private final File jdkJar;

    /**
     * The path to the jsr308 Langtools Type Annotations Compiler
     */
    private final File javacJar;

    /**
     * The paths to the jar containing CheckerMain.class (i.e. checkers.jar)
     */
    private final File thisJar;

    /**
     * Parent of thisJar
     */
    private final File parentDir;

    /**
     * The current major version of the jre in the form 1.X where X is the major version of Java
     */
    private final double jreVersion;


    private final String bootClasspath;

    private final List<String> jvmOpts;

    private final List<String> cpOpts;

    private final List<String> toolOpts;

    /**
     * Construct all the relevant file locations and java version given the path to this jar and
     * a set of directories in which to search for jars
     * @param thisJar The path to this jar
     * @param searchPath Directories in which to search for jars
     */
    public CheckerMain(final String thisJar, final List<String> searchPath, final String [] args) {
        this.thisJar     = new File(thisJar);
        this.parentDir   = this.thisJar.getParentFile();
        this.jreVersion  = getJreVersion();

        final List<File> searchPathFiles = new ArrayList<File>();
        for(final String file : searchPath) {
            searchPathFiles.add(new File(parentDir, file));
        }

        this.javacJar      = findFileInDirectories("javac.jar",      searchPathFiles);
        this.jdkJar        = findFileInDirectories(findJdkJarName(), searchPathFiles);
        assertFilesExist( Arrays.asList(javacJar, jdkJar) );

        final List<String> argsList = new ArrayList<String>(Arrays.asList(args));

        this.bootClasspath = prepFilePath(null, jdkJar, javacJar) + File.pathSeparator +
                             join(File.pathSeparator, extractBootClassPath(argsList));
        this.jvmOpts       = extractJvmOpts(argsList);

        this.cpOpts        = extractCpOpts(argsList);
        this.cpOpts.add(0, this.thisJar.getAbsolutePath());

        this.toolOpts      = argsList;
    }

    /**
     * Construct a file path from files nad prepend it to previous (if previous is not null)
     * @param previous The previous file path to append to (can be null)
     * @param files    The files used to construct a path using File.pathSeparator
     * @return previous with the conjoined file path appended to it or just the conjoined file path if previous is null
     */

    public String prepFilePath(final String previous, File... files) {
        if(files == null || files.length == 0) {
            throw new RuntimeException("Prepending empty or null array to file path! files == " + (files == null ? " null" : " Empty"));
        } else {
            String path = files[0].getAbsolutePath();
            for( int i = 1; i < files.length; i++ ) {
                path += File.pathSeparator + files[i].getAbsolutePath();
            }

            if(previous == null) {
                return path;
            } else {
                return path + File.pathSeparator + previous;
            }
        }
    }

    /**
     * TODO: Either create/use a util class
     */
    public String join(final String delimiter, final List<String> strings) {

        boolean notFirst = false;
        final StringBuffer sb = new StringBuffer();

        for(final String str : strings) {
            if(notFirst) {
                sb.append(delimiter);
            }
            sb.append(str);
            notFirst = true;
        }

        return sb.toString();
    }


    /**
     * Find all args that match the given pattern and extract their index 1 group.  Add all the index 1 groups to the
     * returned list.   Remove all matching args from the input args list.
     * @param pattern      A pattern with at least one matching group
     * @param allowEmpties Whether or not to add empty group(1) matches to the returned list
     * @param args         The arguments to extract from
     * @return A list of arguments from the first group that matched the pattern for each input args or the empty list
     *         if there were none
     */
    protected static List<String> extractOptWPattern(final Pattern pattern, boolean allowEmpties, final List<String> args) {
        final List<String> matchedArgs = new ArrayList<String>();

        int i = 0;
        while(i < args.size()) {
            final Matcher matcher = pattern.matcher(args.get(i));
            if( matcher.matches() ) {
                final String arg = matcher.group(1).trim();

                if( !arg.equals("") || allowEmpties ) {
                    matchedArgs.add(arg);
                }

                args.remove(i);
            } else {
                i++;
            }
        }

        return matchedArgs;
    }

    /**
     * A pattern to catch bootclasspath prepend entries, used to construct one -Xbootclasspath/p: argument
     */
    protected static final Pattern BOOT_CLASS_PATH_REGEX = Pattern.compile("^(?:-J){0,1}-Xbootclasspath/p:(.*)$");

    /**
     * Remove all -Xbootclasspath/p: or -J-Xbootclasspath/p: arguments from args and add them to the returned list
     * @param args The arguments to extract from
     * @return All non-empty arguments matching BOOT_CLASS_PATH_REGEX or an empty list if there were none
     */
    protected static List<String> extractBootClassPath(final List<String> args) {
        return extractOptWPattern(BOOT_CLASS_PATH_REGEX, false, args);
    }

    /**
     * Matches all -J arguments
     */
    protected static final Pattern JVM_OPTS_REGEX = Pattern.compile("^(?:-J)(.*)$");

    /**
     * Remove all -J arguments from args and add them to the returned list
     * @param args The arguments to extract from
     * @return All -j arguments (without the -J prefix) or an empty list if there were none
     */
    protected static List<String> extractJvmOpts(final List<String> args) {
        return extractOptWPattern(JVM_OPTS_REGEX, false, args);
    }

    /**
     * Extract the -cp and -classpath arguments and there immediate predecessors in args.  Return a list of the
     * predecessors.  If NO -cp or -classpath arguments were present then use the current directory and the
     * CLASSPATH environment variable
     * @param args A list of arguments to extract from
     * @return The arguments that should be put on the classpath when calling javac.jar
     */
    protected static List<String> extractCpOpts(final List<String> args) {
        List<String> actualArgs = new ArrayList<String>();

        String path = null;

        int i = 0;
        while(i < args.size()) {

            if( args.get(i).equals("-cp") || args.get(i).equals("-classpath")) {
                if(args.size() > i ) {
                    args.remove(i);
                    path = args.remove(i);
                } //else loop ends and we have a dangling -cp
            } else {
                i++;
            }
        }

        //The logic below is exactly what the javac script does
        //If it's empty use the current directory AND the "CLASSPATH" environment variable
        if( path == null ) {
            actualArgs.add(System.getenv("CLASSPATH"));
            actualArgs.add(".");
        } else {
            //Every classpath entry overrides the one before it and CLASSPATH
            actualArgs.add(path);
        }

        return actualArgs;
    }

    /**
     * Invoke the JSR308 Type Annotations Compiler with all relevant jars on it's classpath or boot classpath
     */
    private int invokeCompiler() {
        List<String> args = new ArrayList<String>(jvmOpts.size() + cpOpts.size() + toolOpts.size() + 5);

        final String java = PluginUtil.getJavaCommand(System.getProperty("java.home"), System.out);
        args.add(java);

        args.add("-Xbootclasspath/p:" + bootClasspath );
        args.add("-ea:com.sun.tools...");

        args.addAll(jvmOpts);

        args.add("-jar");
        args.add(javacJar.getAbsolutePath());

        args.add("-classpath");
        args.add(join(File.pathSeparator, cpOpts));

        args.addAll(toolOpts);

        //Actually invoke the compiler
        return execute(args);
    }

    /**
     * Determine the version of the JRE that we are currently running and select a jdk<V>.jar where
     * <V> is the version of java that is being run (e.g. 6, 7, ...)
     * @return The jdk<V>.jar where <V> is the version of java that is being run (e.g. 6, 7, ...)
     */
    private String findJdkJarName() {
        final String fileName;
        if(jreVersion == 1.4 || jreVersion == 1.5 || jreVersion == 1.6) {
            fileName = "jdk6.jar";
        } else if(jreVersion == 1.7) {
            fileName = "jdk7.jar";
        } else {
            throw new AssertionError("Unsupported JRE version: " + jreVersion);
        }

        return fileName;
    }

    /**
     * Helper class to invoke the libc system() native call
     *
     * Using the system() native call, rather than Runtime.exec(), to handle
     * IO "redirection"
     **/
    public interface CLibrary extends Library {
        CLibrary INSTANCE = (CLibrary)Native.loadLibrary("c", CLibrary.class);
        int system(String command);
     }

    /**
     * Helper method to do the proper escaping of arguments to pass to
     * system()
     */
    static String constructCommand(Iterable<String> args) {
        StringBuilder sb = new StringBuilder();

        for (String arg: args) {
            sb.append('"');
            sb.append(arg.replace("\"", "\\\""));
            sb.append("\" ");
        }

        return sb.toString();
    }

    /** Execute the commands, with IO redirection */
    static int execute(Iterable<String> cmdArray) {
        String command = constructCommand(cmdArray);
        return CLibrary.INSTANCE.system(command);
    }


    /**
     * Extract the first two version numbers from java.version (e.g. 1.6 from 1.6.whatever)
     * @return The first two version numbers from java.version (e.g. 1.6 from 1.6.whatever)
     */
    private static double getJreVersion() {
        final Pattern versionPattern = Pattern.compile("^(\\d\\.\\d+)\\..*$");
        final String  jreVersionStr = System.getProperty("java.version");
        final Matcher versionMatcher = versionPattern.matcher(jreVersionStr);

        final double version;
        if(versionMatcher.matches()) {
            version = Double.parseDouble(versionMatcher.group(1));
        } else {
            throw new RuntimeException("Could not determine version from property java.version=" + jreVersionStr);
        }

        return version;
    }

    /**
     * Find a file by searching each of directories in order
     * Note: if the file does not exist in any of the directories a file object using the last directory as parent
     * is returned but calling .exists() on it will return false
     *
     * @param fileName The name of the file to find
     * @param directories The set of directories to check
     * @return The first file with the given name found in the first directory that contains the file with fileName or
     * a non-existant file with the given name in the last directory (this is so we can at least get the name back
     * from the file without having to hold onto a reference of filename)
     */
    private static File findFileInDirectories(final String fileName, final List<File> directories) {
        assert( directories != null    );
        assert( !directories.isEmpty() );
        assert( fileName    != null    );

        File file = null;
        for(final File dirName : directories) {
            file = new File(dirName, fileName);
            if(file.exists()) {
                break;
            }
        }

        return file;
    }

    /**
     * Find the jar file containing the annotated JDK (i.e. jar containing
     * this file
     */
    public static String findPathJar(Class<?> context) throws IllegalStateException {
        if (context == null) context = CheckerMain.class;
        String rawName = context.getName();
        String classFileName;
        /* rawName is something like package.name.ContainingClass$ClassName. We need to turn this into ContainingClass$ClassName.class. */ {
            int idx = rawName.lastIndexOf('.');
            classFileName = (idx == -1 ? rawName : rawName.substring(idx+1)) + ".class";
        }

        String uri = context.getResource(classFileName).toString();
        if (uri.startsWith("file:")) throw new IllegalStateException("This class has been loaded from a directory and not from a jar file.");
        if (!uri.startsWith("jar:file:")) {
            int idx = uri.indexOf(':');
            String protocol = idx == -1 ? "(unknown)" : uri.substring(0, idx);
            throw new IllegalStateException("This class has been loaded remotely via the " + protocol +
                    " protocol. Only loading from a jar on the local file system is supported.");
        }

        int idx = uri.indexOf('!');
        //As far as I know, the if statement below can't ever trigger, so it's more of a sanity check thing.
        if (idx == -1) throw new IllegalStateException("You appear to have loaded this class from a local jar file, but I can't make sense of the URL!");

        try {
            String fileName = URLDecoder.decode(uri.substring("jar:file:".length(), idx), Charset.defaultCharset().name());
            return new File(fileName).getAbsolutePath();
        } catch (UnsupportedEncodingException e) {
            throw new InternalError("default charset doesn't exist. Your VM is borked.");
        }
    }

    /**
     * Assert that all files in the list exist and if they don't, throw a RuntimeException with a list of the files
     * that do not exist
     *
     * @param expectedFiles Files that must exist
     */
    private static void assertFilesExist(final List<File> expectedFiles) {
        final List<File> missingFiles = new ArrayList<File>();
        for(final File file : expectedFiles) {
            if( file == null || !file.exists() ) {
                missingFiles.add(file);
            }
        }

        if( !missingFiles.isEmpty() ) {
            final File firstMissing = missingFiles.remove(0);
            String message = "The following files could not be located: " + firstMissing.getName();

            for(final File missing : missingFiles) {
                message += ", " + missing.getName();
            }

            throw new RuntimeException(message);
        }
    }
}
