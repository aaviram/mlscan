package org.ovirt.storage;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * A simple parser for mbox-formatted files.
 */
public class MboxParser implements Closeable {
    private InputStream input;
    private List<String> unparseableMessages;

    /**
     * Create a new parser to operate on the given file.
     *
     * @param mboxFile An mbox-formatted file to parse
     * @throws FileNotFoundException
     */
    public MboxParser(File mboxFile) throws FileNotFoundException {
        input = new FileInputStream(mboxFile);
        unparseableMessages = new ArrayList<>();
    }

    /**
     * Parse the messages in this parser's input file and return a list of {@link Email} items.
     *
     * @return List of emails.
     */
    public List<Email> parseMessages() {
        List<Email> messages = new ArrayList<>();
        if (input == null) {
            return messages;
        }

        Scanner s = new Scanner(new BufferedInputStream(input));
        StringBuilder cur = new StringBuilder();

        while (s.hasNextLine()) {
            String line = s.nextLine();
            // The mbox delimiter is /^From /; create a new record if found.
            if (line.startsWith("From ") &&
                cur.length() > 0) {
                    String message = cur.toString();
                    try {
                        messages.add(Email.parseMimeMessage(message));
                    } catch (IllegalStateException e) {
                        unparseableMessages.add(message);
                    }
                    cur = new StringBuilder();
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) {
            // If no messages are in the file, don't append an empty string to the list
            messages.add(Email.parseMimeMessage(cur.toString()));
        }
        s.close();
        input = null;
        return messages;
    }

    /**
     * Close the input file used by this parser.
     *
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        if (input != null) {
            input.close();
            input = null;
        }
    }

    /**
     * Retrieve a list of messages which could not be parsed into {@link Email} objects.
     *
     * @return List of Strings of unparseable messages.
     */
    public List<String> getUnparseableMessages() {
        return unparseableMessages;
    }
}
