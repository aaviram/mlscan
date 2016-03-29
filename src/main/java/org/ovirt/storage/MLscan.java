package org.ovirt.storage;

import com.datumbox.framework.common.dataobjects.Record;
import com.datumbox.framework.applications.nlp.TextClassifier;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.BooleanOptionHandler;
import org.kohsuke.args4j.spi.LongOptionHandler;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Machine learning mailing list scanner application.
 */
public class MLscan {
    private static final String outputFilePrefix = "mlscan.";

    @Option(name = "-c", aliases = {"--confidence"},
            metaVar = "confidenceValue",
            usage = "confidence p-value required to consider a mail interesting")
    double confidenceValue = 0.50;

    @Option(name = "-e", aliases = {"--exclude"},
            metaVar = "excludedDomain",
            required = true,
            usage = "domain of senders whose email to not flag as interesting, e.g. example.com")
    private String excludedDomain;

    @Option(name = "-f", aliases = {"--trainingfiles"},
            handler = StringArrayOptionHandler.class,
            metaVar = "trainingFiles",
            required = true,
            usage = "list of mbox training files")
    private List<String> trainingFilenames;

    @Option(name = "-h", aliases = {"--help"},
            handler = BooleanOptionHandler.class,
            help = true)
    private boolean help;

    private EmailClassifier.ClassificationMethod classificationMethod;
    @Option(name = "-m", aliases = {"--method"},
            metaVar = "method",
            required = true,
            usage = "classification method:"
                    + " \"bernoulli_bayes\", \"binarized_bayes\", \"multinomial_bayes\","
                    + " \"svm\", or \"max_entropy\"")
    private void setClassificationMethod(String classificationMethod) throws CmdLineException {
        try {
            this.classificationMethod = EmailClassifier.ClassificationMethod.valueOf(classificationMethod.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CmdLineException("Invalid classification method \"" + classificationMethod + "\"");
        }
    }

    @Option(name = "-o", aliases = {"--outputdir"},
            metaVar = "outputDirectory",
            required = true,
            usage = "directory used to store temporary and output files")
    private String outputDirectoryStr;

    // StringArrayOptionHandler can't handle quoted multiword arguments
    private List<String> predictTexts;
    @Option(name = "-p", aliases = {"--predict"},
            metaVar = "predictText",
            usage = "test sentence to predict (can be used multiple times")
    private void addPredictText(String sentence) throws CmdLineException {
        if (predictTexts == null) {
            predictTexts = new ArrayList<>();
        }
        predictTexts.add(sentence);
    }

    private long randomSeed;
    private boolean randomSeedSet;
    @Option(name = "-r", aliases = {"--randomseed"},
            handler = LongOptionHandler.class,
            metaVar = "randomSeed",
            usage = "random seed (for repeatability)")
    private void setRandomSeed(Long seed) {
        randomSeed = seed;
        randomSeedSet = true;
    }

    @Option(name = "-s", aliases = {"--senders"},
            handler = StringArrayOptionHandler.class,
            metaVar = "senderAddresses",
            required = true,
            usage = "list of email addresses of those replying to interesting messages")
    private List<String> interestingSenders;

    @Option(name = "-t", aliases = {"--testfiles"},
            handler = StringArrayOptionHandler.class,
            metaVar = "testFilenames",
            usage = "list of mbox test files")
    private List<String> testFilenames;


    private List<File> trainingFiles;
    private List<File> testFiles;
    private File outputDirectory;
    private FileUtil fileUtil;


    /**
     * Instantiate and run the MLscan application:
     *  - parses arguments
     *  - initializes the application class
     *  - runs {@link MLscan#execute()}
     *
     * @param args Arguments
     */
    public static void main(String[] args) {
        MLscan mlscan = new MLscan();

        CmdLineParser parser = new CmdLineParser(mlscan);
        parser.getProperties().withUsageWidth(80);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            mlscan.exitWithUsage(parser);
        }

        if (mlscan.help) {
            mlscan.exitWithUsage(parser);
        }

        mlscan.initialize();
        mlscan.execute();
    }

    private void exitWithUsage(CmdLineParser parser) {
        System.out.format("Usage: <program> [options]\n\nOptions:");
        parser.printSingleLineUsage(System.err);
        System.out.println("\n");
        parser.printUsage(System.err);
        System.exit(1);
    }

    /**
     * Initialize the application after arguments have been parsed.
     */
    private void initialize() {
        convertNullLists();
        setRandomSeed();
        checkPathnames();
        fileUtil = new FileUtil(outputDirectory, outputFilePrefix);
    }

    /**
     * Convert null lists left by args4j into empty lists expected by the application.
     * This is only applicable for non-required arguments.
     */
    private void convertNullLists() {
        if (testFilenames == null) {
            testFilenames = Collections.emptyList();
        }
        if (predictTexts == null) {
            predictTexts = Collections.emptyList();
        }
    }

    /**
     * Ensure the random seed is set.
     */
    private void setRandomSeed() {
        if (!randomSeedSet) {
            Random r = new Random();
            randomSeed = r.nextLong();
        }
    }

    /**
     * Ensure all pathnames specified in command-line options are valid.
     */
    private void checkPathnames() {
        // The training files must exist
        trainingFiles = new ArrayList<>();
        for (String name : trainingFilenames) {
            File file = new File(name).getAbsoluteFile();
            if (!file.isFile() || !file.canRead()) {
                System.err.format("Error: could not read training file %s\n", file);
                System.exit(1);
            }
            trainingFiles.add(file);
        }

        // Specified test files must exist
        testFiles = new ArrayList<>();
        for (String name : testFilenames) {
            File file = new File(name).getAbsoluteFile();
            if (!file.isFile() || !file.canRead()) {
                System.err.format("Error: could not read test file %s\n", file);
                System.exit(1);
            }
            testFiles.add(file);
        }

        // Create the output directory, if necessary
        outputDirectory = new File(outputDirectoryStr).getAbsoluteFile();
        if (outputDirectory.isFile()) {
            System.err.format("Error: output directory %s is a file\n", outputDirectory.getName());
            System.exit(1);
        } else if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            System.err.format("Error: could not create output directory %s is a file\n", outputDirectory.getName());
            System.exit(1);
        } else if (!outputDirectory.canWrite()) {
            System.err.format("Error: unable to write to output directory %s\n", outputDirectory.getName());
            System.exit(1);
        }
    }

    /**
     * Build a classifier using the specified input and test it.
     */
    public void execute() {
        System.out.println("Training with files:");
        trainingFiles.stream().forEach(x -> System.out.println("  " + x));
        List<EmailThreadItem> threads = getThreadsFromMboxFiles(trainingFiles);

        EmailClassifier emailClassifier = new EmailClassifier(
                threads, interestingSenders, excludedDomain,
                classificationMethod, randomSeed, fileUtil);
        TextClassifier classifier = null;
        try {
            classifier = emailClassifier.buildClassifier();
        } catch (IOException e) {
            System.err.format("Failed to create classifier: %s", e);
            System.exit(1);
        }

        testClassifier(classifier);
    }

    /**
     * Retrieve threads from the training files.
     *
     * @param trainingFiles Training files to parse, in mbox format.
     * @return A list of EmailThreadItem objects representing threads within the mbox files.
     */
    private List<EmailThreadItem> getThreadsFromMboxFiles(List<File> trainingFiles) {
        // Parse the mbox files and retrieve the messages within.
        List<Email> messages = new ArrayList<>();
        List<String> unparseableMessages = null;

        for (File trainingFile : trainingFiles) {
            System.out.println("Parsing mbox file " + trainingFile + "...");
            try {
                MboxParser parser = new MboxParser(trainingFile);
                messages.addAll(parser.parseMessages());
                unparseableMessages = parser.getUnparseableMessages();
            } catch (FileNotFoundException e) {
                System.err.format("Unable to read mbox file %s: %s\n", trainingFile.getPath(), e);
                System.exit(1);
            }
        }
        System.out.println();

        if (unparseableMessages != null && unparseableMessages.size() > 0) {
            File errFile = fileUtil.getOutputFile("unparseable");
            try {
                FileUtil.dumpUtf8ToFile(unparseableMessages, "\n\n=== UNPARSEABLE MESSAGE ===\n\n", errFile);
                System.out.format("%d unparseable messages found, written to %s\n",
                        unparseableMessages.size(), errFile.getPath());
            } catch (IOException e) {
                System.err.format("Failed to write unparseable messages to %s\n", errFile.getPath());
            }
        }

        if (messages.isEmpty()) {
            System.err.println("No training messages found");
            System.exit(1);
        }

        // Collate the messages into trees representing the email threads.
        System.out.println("Collating threads...");
        EmailCollator collator = new EmailCollator();
        messages.stream().forEach(collator::add);
        return collator.getEmailThreads();
    }

    /**
     * Test the given classifier against the test sentences and test files, writing stats to
     * an output file and priting a summary to the screen.
     *
     * @param classifier The classifier to test.
     */
    private void testClassifier(TextClassifier classifier) {
        List<String> testOutput = new ArrayList<>();

        // Test our classifier
        if (!predictTexts.isEmpty()) {
            for (String testSentence : predictTexts) {
                StringBuilder sb = new StringBuilder();
                sb.append("Classifying sentence: ").append(testSentence).append("\n");
                Record r = classifier.predict(testSentence.toLowerCase());
                sb.append(getStats(classifier, r));
                testOutput.add(sb.toString());
            }
        }

        if (!testFiles.isEmpty()) {
            // This training set creator is only for evaluating our test messages.
            InterestingSendersTrainingSetCreator testCreator
                    = new InterestingSendersTrainingSetCreator(
                            interestingSenders, excludedDomain, randomSeed, fileUtil);
            List<EmailThreadItem> testThreads = getThreadsFromMboxFiles(testFiles);

            int total = 0;
            int match = 0;
            int falsePositives = 0;
            int fuzzyPositives = 0;
            int totalInteresting = 0;
            int matchInteresting = 0;

            for (EmailThreadItem testThread : testThreads) {
                // The message should be labelled interesting if any message in the thread was sent
                // by one of the interestingSenders.
                TrainableClassification expected = testCreator.getMessageClass(testThread);
                if (!expected.useForTraining()) continue;  // Ignored

                Record r = classifier.predict(
                        InterestingSendersTrainingSetCreator.getClassificationDataFromMail(testThread));

                if (expected.toString().equals("Interesting")) totalInteresting++;
                InterestingSendersTrainingSetCreator.Classification fuzzy
                        = (confidenceValue != 0.5F
                                && (double) r.getYPredictedProbabilities().get("Interesting") > confidenceValue)
                        ? InterestingSendersTrainingSetCreator.Classification.INTERESTING
                        : InterestingSendersTrainingSetCreator.Classification.NOT_INTERESTING;

                if (r.getYPredicted().equals(expected.toString())) {
                    if (r.getYPredicted().equals("Interesting")) matchInteresting++;
                    match++;

                    if (fuzzy != expected) {
                        falsePositives++;
                    }
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Classifying mail: ").append(testThread.getEmail().getSummary()).append("\n");
                    sb.append("Expected class: ").append(expected).append("\n");
                    sb.append(r.getYPredictedProbabilities()).append("\n");
                    sb.append(getStats(classifier, r));

                    if (fuzzy == expected) {
                        fuzzyPositives++;
                        sb.append("Fuzzy match\n");
                    }
                    testOutput.add(sb.toString());
                }
                total++;
            }

            StringBuilder sb = new StringBuilder("\n");
            sb.append(String.format(
                    "%d messages total, %d predicted successfully (%.2f%%)\n",
                    total, match,
                    100.0 * match / total));
            sb.append(String.format(
                    "%d uninteresting messages, %d predicted successfully (%.2f%%)\n",
                    total - totalInteresting, match - matchInteresting,
                    100.0 * (match - matchInteresting) / (total - totalInteresting)));
            sb.append(String.format(
                    "%d interesting messages, %d predicted successfully (%.2f%%)\n",
                    totalInteresting, matchInteresting,
                    100.0 * matchInteresting / totalInteresting));

            if (confidenceValue != 0.5) {
                sb.append("\n");
                sb.append(String.format(
                        "Results for positive match at %.2f%% probability of being interesting:\n",
                        confidenceValue * 100));
                sb.append(String.format(
                        "%d messages total, %d predicted successfully (%.2f%%)\n",
                        total, match + fuzzyPositives - falsePositives,
                        100.0 * (match + fuzzyPositives - falsePositives) / total));
                sb.append(String.format(
                        "%d uninteresting messages, %d predicted successfully (%.2f%%) (%d more false positives)\n",
                        total - totalInteresting,
                        match - matchInteresting - falsePositives,
                        100.0 * (match - matchInteresting - falsePositives) / (total - totalInteresting),
                        falsePositives));
                sb.append(String.format(
                        "%d interesting messages, %d predicted successfully (%.2f%%) (%d more matches)\n",
                        totalInteresting, matchInteresting + fuzzyPositives,
                        100.0 * (matchInteresting + fuzzyPositives) / totalInteresting,
                        fuzzyPositives));
            }

            testOutput.add(sb.toString());
            System.out.println(sb.toString());
        }

        File testResults = fileUtil.getOutputFile("testResults");
        try {
            FileUtil.dumpUtf8ToFile(testOutput, "===\n", testResults);
            System.out.println("Wrote test results to " + testResults.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("Failed to write test results to " + testResults.getAbsolutePath());
        }
    }

    /**
     * Return a string with statistics from a match with the given record.
     *
     * @param classifier Classifier used for the match
     * @param r Record matched
     * @return String summary of statistics
     */
    private String getStats(TextClassifier classifier, Record r) {
        return new StringBuilder()
                .append("Predicted class: ").append(r.getYPredicted()).append("\n")
                .append("Probability: ").append(r.getYPredictedProbabilities().get(r.getYPredicted())).append("\n")
                //.append("Classifier Statistics: ").append(PHPMethods.var_export(classifier.getValidationMetrics())));
                .toString();
    }
}
