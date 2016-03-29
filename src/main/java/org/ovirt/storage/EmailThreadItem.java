package org.ovirt.storage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents an item within an email thread.  The thread is navigable, having links
 * to its parent and children.
 */
public class EmailThreadItem {
    private Email email;
    private EmailThreadItem parent;
    private List<EmailThreadItem> children;

    /**
     * Create a new, detached (no parent/children) email thread item with the specified
     * {@link Email}.
     *
     * @param email The email this item should hold.
     */
    public EmailThreadItem(Email email) {
        this.email = email;
        children = new ArrayList<>();
    }

    /**
     * Returns the email object for this particular thread item.
     *
     * @return {@link Email} held by this item.
     */
    public Email getEmail() {
        return email;
    }

    /**
     * Returns the root of the thread containing this email.
     *
     * @return Root {@link EmailThreadItem} of this thread.
     */
    public EmailThreadItem getRoot() {
        EmailThreadItem root = this;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return root;
    }

    /**
     * Returns the parent of this message.
     *
     * @return Parent {@link EmailThreadItem}
     */
    public EmailThreadItem getParent() {
        return parent;
    }

    /**
     * Sets the parent of this message.
     *
     * @param parent Parent {@link EmailThreadItem}
     */
    public void setParent(EmailThreadItem parent) {
        this.parent = parent;
    }

    /**
     * Retrieve children of this message.  This is not a defensive copy, and thus may
     * be modified by the caller.
     *
     * @return List of {@link EmailThreadItem} representing child emails.
     */
    public List<EmailThreadItem> getChildren() {
        return children;
    }

    /**
     * Retrieve all messages in this thread, including the root, as a new, flattened list
     * suitable for iteration.  The messages are not guaranteed to be in any particular order.
     *
     * @return List of {@link EmailThreadItem} representing emails in this thread.
     */
    public List<EmailThreadItem> getAllInThread() {
        List<EmailThreadItem> list = new ArrayList<>();
        list.add(this);

        // A thread is a tree; a simple BFS will provide all descendants.
        int i = 0;
        do {
            list.addAll(list.get(i++).getChildren());
        } while (i < list.size());
        return list;
    }

    /**
     * Returns a multi-line representation of the email thread with one message per line.
     * The string ends in a newline.
     */
    @Override
    public String toString() {
        return toStringCustom(t -> t.getEmail().getSummary());
    }

    @FunctionalInterface
    interface EmailThreadPrinter {
        String getString(EmailThreadItem thread);
    }

    /**
     * Return a string representing the hierarchy of this email thread, using a custom method
     * to determine what to print for each encountered message.
     *
     * @param printer An {@link EmailThreadPrinter} to handle output for each encountered message
     * @return A String representing this thread
     */
    public String toStringCustom(EmailThreadPrinter printer) {
        StringBuilder sb = new StringBuilder();

        // We don't know how deep the tree is, so we avoid recursion and do an iterative DFS.
        Deque<EmailThreadItem> dfs = new ArrayDeque<>();
        Map<EmailThreadItem, Integer> depths = new HashMap<>();

        // We draw vertical lines to connect sibling nodes in the tree.  Tracking the count of remaining
        // nodes at each depth allows us to print these only if there is a sibling to connect to.
        Map<Integer, Integer> countAtDepth = new HashMap<>();

        dfs.push(this);
        depths.put(this, 0);
        countAtDepth.put(0, 1);

        while (!dfs.isEmpty()) {
            EmailThreadItem thread = dfs.pop();
            int depth = depths.remove(thread);
            countAtDepth.put(depth, countAtDepth.get(depth) - 1);

            for (int i = 1; i < depth; i++) {
                sb.append(countAtDepth.get(i) == 0 ? "    " : "|   ");
            }
            if (depth > 0) {
                sb.append("+-- ");
            }
            sb.append(printer.getString(thread)).append("\n");

            if (!countAtDepth.containsKey(depth + 1)) {
                countAtDepth.put(depth + 1, 0);
            }
            thread.children.stream()
                    .forEach(x -> {
                            dfs.push(x);
                            depths.put(x, depth + 1);
                            countAtDepth.put(depth + 1, countAtDepth.get(depth + 1) + 1);
                    });
        }

        return sb.toString();
    }
}
