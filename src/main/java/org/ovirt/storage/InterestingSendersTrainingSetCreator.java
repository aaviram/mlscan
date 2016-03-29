package org.ovirt.storage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Create training sets.  For a set of email threads, if any mail in the thread was sent by
 * a sender specified in the senders list given to the constructor and not sent by a sender
 * in the excluded domain, the root of the thread is considered an interesting message.
 *
 * In short, this attempts to flag threads that should get the attention of one of the
 * specified sender addresses.
 *
 * The data returned by the set is a processed set of email text used for classification; an
 * email can be similarly processed by calling the getClassificationDataFromMail() method.
 */
public class InterestingSendersTrainingSetCreator {
    private Set<String> senders;
    private String excludedDomain;
    private long randomSeed;
    private FileUtil fileUtil;  // TODO DI would be nice for this

    private Map<String, Classification> messageClassifications;  // messageId -> classification
    private Map<Classification, Integer> classificationCounts;  // classification -> count

    public enum Classification implements TrainableClassification {
        INTERESTING("Interesting", true),
        NOT_INTERESTING("NotInteresting", true),
        IGNORED("Ignored", false);

        String text;
        boolean useForTraining;

        Classification(String text, boolean useForTraining) {
            this.text = text;
            this.useForTraining = useForTraining;
        }

        @Override
        public boolean useForTraining() {
            return useForTraining;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * Create a new training set creator.
     *
     * @param senders List of sender email addresses (name@domain.com format) whose replies
     *                indicate an interesting email thread
     * @param excludedDomain Domain of senders whose emails should never be considered interesting
     * @param randomSeed Random seed used for any sampling done in the class
     * @param fileUtil A FileUtil instance for writing output data
     */
    public InterestingSendersTrainingSetCreator(Collection<String> senders, String excludedDomain,
            long randomSeed, FileUtil fileUtil) {
        this.senders = new HashSet<>(senders);
        this.excludedDomain = excludedDomain;
        this.randomSeed = randomSeed;
        this.fileUtil = fileUtil;

        messageClassifications = new HashMap<>();
        classificationCounts = new HashMap<>();
        Arrays.stream(Classification.values()).forEach(c -> classificationCounts.put(c, 0));
    }

    /**
     * Build training sets using this training set creator.
     *
     * @param threads Email threads to process.
     * @return A map of Lists of training examples, indexed by each list's {@link TrainableClassification}.
     */
    public Map<TrainableClassification, List<String>> getTrainingSets(List<EmailThreadItem> threads) {
        Map<Classification, List<String>> sets = classifyMessages(threads);
        removeUncommonWords(sets, 4);
        balanceTrainingSets(sets);
        return new HashMap<>(sets);
    }

    /**
     * Generate a map keyed by message classification containing a list of strings of training
     * data for each classification.  Data is generated based on the content of emails in the
     * given threads.
     *
     * @param threads input data to classify
     * @return map of Classification -&gt; List of Strings of training data
     */
    private Map<Classification, List<String>> classifyMessages(List<EmailThreadItem> threads) {
        Map<Classification, List<String>> sets = new HashMap<>();
        Arrays.stream(Classification.values())
                .forEach(c -> sets.put(c, new ArrayList<>()));
        for (EmailThreadItem thread : threads) {
            // Ignore messages that are orphaned
            if (!thread.getEmail().getInReplyTo().equals("")) {
                continue;
            }

            // Choose one method below, either classify first message or all messages in the thread.
            classifyFirstMessage(thread, sets);
            // classifyThread(thread, sets);
        }
        return sets;
    }

    /**
     * Remove all words from the training sets that appear only up to maxOccurrences times.
     *
     * @param sets Training sets to process
     * @param minOccurrences Minimum occurrence count at which words are allowed to stay in the
     *                       training sets
     */
    private void removeUncommonWords(Map<Classification, List<String>> sets, int minOccurrences) {
        // Build a map of words and counts, then remove words occurring only once.
        // This might be a little slow...
        Map<String, Integer> wordFrequencies = new HashMap<>();
        Map<Integer, Integer> frequencyCounts = new HashMap<>();

        System.out.println("Building frequency list...");

        sets.values().stream()
                .flatMap(List::stream)  // stream of List<String> -> stream of String
                .flatMap(example -> Arrays.stream(example.split("\\s+")))  // stream of examples -> stream of words
                .forEach(word -> {
                        int oldCount = wordFrequencies.getOrDefault(word, 0);
                        wordFrequencies.put(word, oldCount + 1);
                        if (oldCount > 0) {
                            int freqCount = frequencyCounts.get(oldCount);
                            if (freqCount == 1) {
                                frequencyCounts.remove(oldCount);
                            } else {
                                frequencyCounts.put(oldCount, freqCount - 1);
                            }
                        }
                        frequencyCounts.put(oldCount + 1, frequencyCounts.getOrDefault(oldCount + 1, 0) + 1);
                    });

        StringBuilder sb = new StringBuilder();
        frequencyCounts.keySet().stream()
                .sorted()
                .forEach(freq -> {
                            sb.append("\n===\n").append(freq).append(" occurrences\n  ");
                            wordFrequencies.entrySet().stream()
                                    .filter(es -> es.getValue().equals(freq))
                                    .forEach(es -> sb.append(" ").append(es.getKey()));
                        });
        sb.append("\n");
        try {
            File frequencyFile = fileUtil.getOutputFile("wordFrequency");
            FileUtil.dumpUtf8ToFile(sb.toString(), frequencyFile);
            System.out.println("Word frequency list written to " + frequencyFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error writing word frequency file");
        }

        System.out.format("Removing uncommon words (less than %d occurrences)...\n", minOccurrences);
        sets.values().stream()
                .forEach(list -> {
                        for (int i = 0; i < list.size(); i++) {
                            list.set(i, Arrays.stream(list.get(i).split("\\s+"))
                                    .filter(word -> wordFrequencies.get(word) >= minOccurrences)
                                    .collect(Collectors.joining(" ")));
                        }
                });

        System.out.println("Done.");
    }

    /**
     * Balance the training sets with respect to number of samples per classification.
     *
     * @param sets Map of training sets to balance
     */
    private void balanceTrainingSets(Map<Classification, List<String>> sets) {
        // Ensure balanced training sets by picking random entries from the larger
        // set of training examples to match the size of the smaller set.
        Classification largerClass = Classification.NOT_INTERESTING;
        Classification smallerClass = Classification.INTERESTING;
        for (Classification c : Classification.values()) {
            if (!c.useForTraining()) continue;
            if (sets.get(c).size() > sets.get(largerClass).size()) largerClass = c;
            if (sets.get(c).size() < sets.get(smallerClass).size()) smallerClass = c;
        }

        // Reservoir sampling
        Random rand = new Random(randomSeed);
        List<String> oldList = sets.get(largerClass);
        List<String> newList = new ArrayList<>(oldList.subList(0, sets.get(smallerClass).size()));

        for (int i = newList.size(); i < oldList.size(); i++) {
            int r = rand.nextInt(i + 1);
            if (r < newList.size()) {
                newList.set(r, oldList.get(i));
            }
        }

        sets.put(largerClass, newList);
        System.out.println("Included " + newList.size() + " entries in training set.");
    }


    /**
     * Retrieve processed classification string for a given email.  This returns the concatenated
     * subject and body, with the following transformations:
     * - log-like entries are removed from the email body
     * - multipart email parts are removed from the email body
     * - lowercase conversion performed on result
     * - punctuation is removed from the result
     * - single-character words are removed from the result
     * - top 50 English language words (and their common verb forms) are removed from the result
     *
     * @param message Message to process
     * @return Processed message
     */
    public static String getClassificationDataFromMail(EmailThreadItem message) {
        return removeStopWords(removeSingleCharWords(removePunctuation(
                message.getEmail().getSubject().toLowerCase() + " "
                + removeLogs(removeAttachmentText(message.getEmail().getBody().toLowerCase())))));
    }

    private static String removeAttachmentText(String body) {
        // This is found only at the end of messages; dotall mode (?s) strips the whole suffix.
        return body.replaceAll("(?s)-------------- next part --------------.* ", "");
    }

    private static String removeLogs(String body) {
        // By default, '.' will match only within a line.
        return body.replaceAll(".*201\\d-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}.*", "");
    }

    private static String removePunctuation(String body) {
        return body.replaceAll("[^ A-Za-z0-9]", " ");
    }

    private static String removeSingleCharWords(String body) {
        return body.replaceAll("\\b\\S\\b", "");
    }

    private static String removeStopWords(String body) {
        // Top 50 most common words according to wikipedia, with common verb forms.
        return body.replaceAll("\\b(?:"
                + "the|be|to|of|and|a|in|that|have|i"
                + "|it|for|not|on|with|he|as|you|do|at"
                + "|this|but|his|by|from|they|we|say|her|she"
                + "|or|an|will|my|one|all|would|there|their|what"
                + "|so|up|out|if|about|who|get|which|go|me"
                + "|was|were|are|is|had|did|done|said|went"
                + ")\\b", "");
    }

    /**
     * Return the classification of the thread, based on whether any interesting senders replied.
     * This does not take into account the excluded domain.
     *
     * @param thread Thread to classify
     * @return Classification
     */
    private Classification getThreadClass(EmailThreadItem thread) {
        boolean flagThread = thread.getAllInThread().stream()
                .anyMatch(x -> senders.contains(x.getEmail().getFromAddress()));
        return (flagThread ? Classification.INTERESTING : Classification.NOT_INTERESTING);
    }

    /**
     * Return the classification of a given message, accounting for interesting senders and
     * the excluded domain.
     *
     * @param message Message to classify
     * @return Classification
     */
    public TrainableClassification getMessageClass(EmailThreadItem message) {
        if (!message.getEmail().getInReplyTo().isEmpty()) {
            return Classification.IGNORED;
        }
        if (excludedDomain != null && message.getEmail().getFromAddress().endsWith(excludedDomain)) {
            return Classification.IGNORED;
        }
        return getThreadClass(message);
    }

    /**
     * Classify the given message, storing it in the proper training set.  It is considered
     * interesting based on the result of {@link #getThreadClass(EmailThreadItem)}, unless
     * it's from a sender in the excluded domain.
     *
     * (The API is a bit strange, but prevents the replies in the thread from having to be scanned
     * multiple times.)
     *
     * @param message Message to classify
     * @param sets Training set map to update
     */
    private void classifyFirstMessage(EmailThreadItem message, Map<Classification, List<String>> sets) {
        Classification msgClass = getThreadClass(message);
        if (excludedDomain != null && message.getEmail().getFromAddress().endsWith(excludedDomain)) {
            msgClass = Classification.IGNORED;
        }

        sets.get(msgClass).add(getClassificationDataFromMail(message));
        messageClassifications.put(message.getEmail().getMessageId(), msgClass);
        classificationCounts.put(msgClass, classificationCounts.get(msgClass) + 1);
    }

    /**
     * Similar to {@link #classifyFirstMessage(EmailThreadItem, Map)}, except all messages in the
     * thread may be considered interesting.
     *
     * @param thread Email thread to classify
     * @param sets Training set map to update
     */
    private void classifyThread(EmailThreadItem thread, Map<Classification, List<String>> sets) {
        Classification threadClass = getThreadClass(thread);

        thread.getAllInThread().stream()
                .forEach(t -> {
                        Classification msgClass
                                = ((excludedDomain != null && t.getEmail().getFromAddress().endsWith(excludedDomain))
                                ? Classification.IGNORED
                                : threadClass);
                        sets.get(msgClass).add(getClassificationDataFromMail(t));
                        messageClassifications.put(t.getEmail().getMessageId(), msgClass);
                        classificationCounts.put(msgClass, classificationCounts.get(msgClass) + 1);
                });
    }

    /**
     * Look up classification for email, useful for test/debug purposes.
     *
     * @param messageId Message id of the email
     * @return Classification for email
     */
    public TrainableClassification getClassification(String messageId) {
        return messageClassifications.getOrDefault(messageId, Classification.IGNORED);
    }

    /**
     * Return the count of each classification type.  The map is a copy of the data used by the
     * training set creator and is safe for modification by the caller.
     *
     * @return Map of string classification type -&gt; count of items in that set.
     */
    public Map<TrainableClassification, Integer> getClassificationCounts() {
        return new HashMap<>(classificationCounts);
    }
}
