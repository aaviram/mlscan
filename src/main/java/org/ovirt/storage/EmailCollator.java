package org.ovirt.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Collates emails into a list of threads.  Collation is performed by matching email in-reply-to
 * values with their target message-id values, forming parent-child relationships represented by
 * the EmailThreadItem objects.
 *
 * Email messages can be added in any order; the collation is done online as emails are added.
 * The end result may end up with orphaned emails if no parent was ever found; to remove these,
 * look for root messages with a in-reply-to id.
 *
 * Note that it stores a reference to the email object given to it (not a copy), as well as a
 * mapping from messages ids to emails, so memory use will increase as emails are added.
 */
public class EmailCollator {
    Map<String, EmailThreadItem> mailsByMessageId;

    // A map of lists of messages keyed by the reply-to-ids we haven't seen.
    Map<String, List<EmailThreadItem>> rootMessages;

    public EmailCollator() {
        mailsByMessageId = new HashMap<>();
        // Insertion order is nice for usability when viewing the results.
        rootMessages = new LinkedHashMap<>();
    }

    /**
     * Add an email to be collated.
     *
     * @param email Email to add.
     */
    public void add(Email email) {
        EmailThreadItem thread = new EmailThreadItem(email);
        mailsByMessageId.put(thread.getEmail().getMessageId(), thread);

        // Search for parent; appending to parent or adding to the map of orphan mail threads.
        EmailThreadItem parent = mailsByMessageId.get(thread.getEmail().getInReplyTo());
        if (parent != null) {
            parent.getChildren().add(thread);
            thread.setParent(parent);
        } else {
            // No parent; we're an orphan.  We may join other orphans with the same inReplyTo id.
            String inReplyTo = thread.getEmail().getInReplyTo();
            if (!rootMessages.containsKey(inReplyTo)) {
                rootMessages.put(inReplyTo, new ArrayList<>());
            }
            rootMessages.get(inReplyTo).add(thread);
        }

        // Search for children; move them from the root list to the proper thread.
        if (rootMessages.containsKey(thread.getEmail().getMessageId())) {
            thread.getChildren().addAll(rootMessages.remove(thread.getEmail().getMessageId()));
        }
    }

    /**
     * Return a view of mutable email thread items which are the roots of their respective
     * threads (or partial threads, if no parent for the item was found during collation).
     * Items are returned in the order they were added to the collator.
     *
     * @return List of root EmailThreadItem objects.
     */
    public List<EmailThreadItem> getEmailThreads() {
        return rootMessages.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }
}
