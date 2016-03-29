package org.ovirt.storage;

import com.datumbox.framework.applications.nlp.TextClassifier;
import com.datumbox.framework.common.Configuration;
import com.datumbox.framework.common.utilities.RandomGenerator;
import com.datumbox.framework.core.machinelearning.classification.BernoulliNaiveBayes;
import com.datumbox.framework.core.machinelearning.classification.BinarizedNaiveBayes;
import com.datumbox.framework.core.machinelearning.classification.MaximumEntropy;
import com.datumbox.framework.core.machinelearning.classification.MultinomialNaiveBayes;
import com.datumbox.framework.core.machinelearning.classification.SupportVectorMachine;
import com.datumbox.framework.core.machinelearning.common.interfaces.ValidationMetrics;
import com.datumbox.framework.core.machinelearning.featureselection.categorical.ChisquareSelect;
import com.datumbox.framework.core.utilities.text.extractors.NgramsExtractor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wraps classifier creation for the email classifier.
 */
public class EmailClassifier {
    public enum ClassificationMethod {
        BERNOULLI_BAYES,
        BINARIZED_BAYES,
        MULTINOMIAL_BAYES,
        SVM,
        MAX_ENTROPY,
    }

    private List<EmailThreadItem> threads;
    private List<String> interestingSenders;
    private String excludedDomain;
    private ClassificationMethod method;
    private Long randomSeed;
    private FileUtil fileUtil;  // TODO DI would be nice for this

    public EmailClassifier(
            List<EmailThreadItem> threads,
            List<String> interestingSenders,
            String excludedDomain,
            ClassificationMethod method,
            Long randomSeed,
            FileUtil fileUtil) {
        this.threads = threads;
        this.interestingSenders = interestingSenders;
        this.excludedDomain = excludedDomain;
        this.method = method;
        this.randomSeed = randomSeed;
        this.fileUtil = fileUtil;
    }

    /**
     * Builds a classifier from the values in this class instance.
     *
     * @return Datumbox text classifier.
     * @throws IOException Error writing classifier training files.
     */
    public TextClassifier buildClassifier() throws IOException {

        // Classify the training mail and create training sets
        InterestingSendersTrainingSetCreator setCreator =
                new InterestingSendersTrainingSetCreator(
                        interestingSenders, excludedDomain, randomSeed, fileUtil);
        Map<TrainableClassification, List<String>> trainingSets = setCreator.getTrainingSets(threads);

        // Create files to serve as input for classifier
        Map<String, File> trainingSetFiles = new HashMap<>();
        for (TrainableClassification classification : trainingSets.keySet()) {
            File trainingFile = fileUtil.getOutputFile(classification.toString());
            System.out.format("Writing training file %s...\n", trainingFile.getPath());
            List<String> trainingSet = trainingSets.get(classification).stream()
                    .map(x -> x.replace('\n', ' ').toLowerCase())
                    .collect(Collectors.toList());
            FileUtil.dumpUtf8ToFile(trainingSet, "\n", trainingFile);

            if (classification.useForTraining()) {
                trainingSetFiles.put(classification.toString(), trainingFile);
            }
        }

        // Print some information about what we're doing
        dumpMessageClassifications(setCreator, fileUtil.getOutputFile("messages"));
        printTrainingStats(setCreator);

        return getClassifier(trainingSetFiles);
    }

    /**
     * Create the classifier based on the training set files.
     *
     * @param trainingSetFiles Mapping of classification name -> training set file, each used
     *                         as input to the Datumbox classifier fit() method.
     * @return Datumbox text classifier.
     */
    private TextClassifier getClassifier(Map<String, File> trainingSetFiles) {
        // This is largely borrowed from the datumbox example usage.
        RandomGenerator.setGlobalSeed(randomSeed);
        Configuration conf = Configuration.getConfiguration();

        Map<Object, URI> dataset = new HashMap<>();
        for (String classification : trainingSetFiles.keySet()) {
            dataset.put(classification, trainingSetFiles.get(classification).toURI());
        }

        TextClassifier.TrainingParameters trainingParameters = new TextClassifier.TrainingParameters();

        switch (method) {
        case BERNOULLI_BAYES:
            trainingParameters.setModelerClass(BernoulliNaiveBayes.class);
            trainingParameters.setModelerTrainingParameters(new BernoulliNaiveBayes.TrainingParameters());
            break;
        case MULTINOMIAL_BAYES:
            trainingParameters.setModelerClass(MultinomialNaiveBayes.class);
            trainingParameters.setModelerTrainingParameters(new MultinomialNaiveBayes.TrainingParameters());
            break;
        case BINARIZED_BAYES:
            trainingParameters.setModelerClass(BinarizedNaiveBayes.class);
            trainingParameters.setModelerTrainingParameters(new BinarizedNaiveBayes.TrainingParameters());
            break;
        case SVM:
            trainingParameters.setModelerClass(SupportVectorMachine.class);
            trainingParameters.setModelerTrainingParameters(new SupportVectorMachine.TrainingParameters());
            break;
        case MAX_ENTROPY:
            trainingParameters.setModelerClass(MaximumEntropy.class);
            trainingParameters.setModelerTrainingParameters(new MaximumEntropy.TrainingParameters());
            break;
        }

        trainingParameters.setDataTransformerClass(null);
        trainingParameters.setDataTransformerTrainingParameters(null);

        trainingParameters.setFeatureSelectorClass(ChisquareSelect.class);
        ChisquareSelect.TrainingParameters chisquareParameters = new ChisquareSelect.TrainingParameters();
        chisquareParameters.setALevel(0.10);  // p-ratio; the default, made explicit
        trainingParameters.setFeatureSelectorTrainingParameters(chisquareParameters);

        trainingParameters.setTextExtractorClass(NgramsExtractor.class);
        NgramsExtractor.Parameters ngramParameters = new NgramsExtractor.Parameters();
        ngramParameters.setMaxCombinations(1);  // The default, made explicit
        trainingParameters.setTextExtractorParameters(ngramParameters);

        TextClassifier classifier = new TextClassifier("InterestingSendersClassifier", conf);
        classifier.fit(dataset, trainingParameters);

        ValidationMetrics vm = classifier.validate(dataset);
        classifier.setValidationMetrics(vm); //store them in the model for future reference

        return classifier;
    }

    /**
     * Write out threads and the classification of each message within.
     *
     * @param setCreator The training set creator
     * @param output File to write
     */
    private void dumpMessageClassifications(InterestingSendersTrainingSetCreator setCreator, File output)
            throws IOException {
        System.out.println("Writing message classifications...");
        FileUtil.dumpUtf8ToFile(threads.stream()
                .map(x -> x.toStringCustom(t -> t.getEmail().getSummary() + " - "
                        + setCreator.getClassification(t.getEmail().getMessageId())))
                .collect(Collectors.toList()), "===\n", output);
        System.out.format("Thread classifications written to %s\n", output.getPath());
    }

    /**
     * Display information about the message counts and classifier.
     *
     * @param setCreator The training set creator
     */
    private void printTrainingStats(InterestingSendersTrainingSetCreator setCreator) {
        Map<TrainableClassification, Integer> counts = setCreator.getClassificationCounts();
        System.out.format("\nTraining with messages in %d threads: %s\n",
                threads.size(),
                counts.keySet().stream()
                        .map(c -> String.format("%d %s", counts.get(c), c))
                        .collect(Collectors.joining(", ")));
        System.out.format("Using classifier: %s\n\n", method.toString().toLowerCase());
    }
}
