package org.ovirt.storage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public class FileUtil {
    private File outputDirectory;
    private String outputFilePrefix;

    /**
     * Create a FileUtil instance with the given output file properties.
     *
     * @param outputDirectory Directory in which to place output files
     * @param outputFilePrefix Prefix of created output files
     */
    public FileUtil(File outputDirectory, String outputFilePrefix) {
        this.outputDirectory = outputDirectory;
        this.outputFilePrefix = outputFilePrefix;
    }

    /**
     * Generate an mlscan-specific output file object in the program's output directory.
     *
     * @param suffix the suffix for the file, resulting in e.g. &lt;outputdir&gt;/mlscan.&lt;suffix&gt;.
     * @return a File object for the output file.
     */
    public File getOutputFile(String suffix) {
        return new File(outputDirectory, outputFilePrefix + suffix);
    }

    /**
     * Dump UTF-8 records to the given file using the specified delimiter (or newline if null).
     *
     * @param records List of String to write to file
     * @param delimiter Delimiter between records
     * @param file Output file
     * @throws IOException
     */
    // TODO: If operating on larger data sets, it would be better to accept a stream.
    // TODO use Files and Path
    public static void dumpUtf8ToFile(List<String> records, String delimiter, File file) throws IOException {
        byte[] delimiterBytes = (delimiter != null ? delimiter : "\n").getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[]{};

        try (FileOutputStream output = new FileOutputStream(file)) {
            for (String line : records) {
                output.write(d);  // Write nothing on the first iteration
                d = delimiterBytes;
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Dump a UTF-8 string to the given file.
     *
     * @param str String to write
     * @param file Output file
     * @throws IOException
     */
    public static void dumpUtf8ToFile(String str, File file) throws IOException {
        dumpUtf8ToFile(Collections.singletonList(str), "", file);
    }
}
