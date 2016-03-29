MLscan
=======

Machine Learning Mailing List Scanner
-------------------------------------
This program attempts to predict if a message sent to a mailing list will be interesting based upon features extracted from similar messages previously sent to the mailing list.  It does this by using a Multinomial Naive Bayes classifier.

The inspiration for the project was to try and automatically flag messages to the ovirt-users mailing list that should be replied to by specific groups.  For example, if someone sends a message with a storage-related query, then someone else familiar with storage could be automatically notified of the incoming message.  This can: lower the amount of mail that people need to scan, help people not accidentally overlook messages they should answer, and lower the response time between when a message is sent to the list and when a reply is made.


Status
------
This is currently a proof of concept.  As such, it has provisions to train the classifier and test it against a given set of data, but it has no means to look for new messages, notify interested parties, create a list of the most interesting new mail, etc. that would be needed in order to deploy it to real-world use.


Program Flow
------------
The program does the following:

1. Parses mailing list archive files from a text mbox format using a /^From / delimiter.
1. Parses individual messages using Apache mime4j.
1. Collates email threads into trees using the Message-Id and In-Reply-To headers.
1. Labels training examples using the "Interesting Senders" heuristic (see below).
1. Trains the classifier: a Multinomial Naive Bayes classifier from the Datumbox Machine Learning Framework.
1. Tests the classifier against new inputs.


Interesting Senders Heuristic
-----------------------------
The class labels for training data are derived using a simple heuristic: we assume a given set of email addresses correspond to a set of people with some specific knowledge.  If one of these people replies to a thread, then it's likely that thread has relevant subject matter; thus, we can classify a thread based upon the set of people that replied to it.


Usage
-----
First, download some mailing list archives from e.g. mailman and decompress them.

Next, divide them such that some messages are part of a training set and some are test data.  Provide the appropriate filenames to the respective mlscan options.

See the mlscan.sh file for example usage.
