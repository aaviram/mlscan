package org.ovirt.storage;

import org.apache.james.mime4j.parser.AbstractContentHandler;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.Field;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable object representing an email.
 *
 * Getters will return values that are possibly empty, but will not be null.
 */
class Email {
    private final String fromAddress;
    private final String fromName;
    private final String to;
    private final String cc;
    private final String date;
    private final String subject;
    private final String messageId;
    private final String inReplyTo;
    private final String body;

    private Email(String fromAddress, String fromName, String to, String cc, String date,
            String subject, String messageId, String inReplyTo, String body) {
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.to = to;
        this.cc = cc;
        this.date = date;
        this.subject = subject;
        this.messageId = messageId;
        this.inReplyTo = inReplyTo;
        this.body = body;
    }

    /**
     * Builder (fluent interface) used to construct an {@link Email}.
     */
    public static class Builder {
        private String fromAddress;
        private String fromName = "";
        private String to = "";  // Implicitly sent to the mailing list
        private String cc = "";
        private String date;
        private String subject;
        private String messageId;
        private String inReplyTo = "";
        private String body;

        public Email.Builder fromAddress(String fromAddress) {
            this.fromAddress = fromAddress;
            return this;
        }

        public Email.Builder fromName(String fromName) {
            this.fromName = fromName;
            return this;
        }

        public Email.Builder to(String to) {
            this.to = to;
            return this;
        }

        public Email.Builder cc(String cc) {
            this.cc = cc;
            return this;
        }

        public Email.Builder date(String date) {
            this.date = date;
            return this;
        }

        public Email.Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Email.Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Email.Builder inReplyTo(String inReplyTo) {
            this.inReplyTo = inReplyTo;
            return this;
        }

        public Email.Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * Return a constructed {@link Email} object.
         *
         * @return The email.
         */
        public Email build() {
            if (fromAddress == null || date == null || subject == null || messageId == null || body == null) {
                throw new IllegalStateException("Invalid email");
            }
            return new Email(fromAddress, fromName, to, cc, date, subject, messageId, inReplyTo, body);
        }
    }

    /**
     * Parse the supplied input string into an {@link Email} message.
     *
     * @param input Input string
     * @return Parsed result as an Email object
     */
    public static Email parseMimeMessage(String input) {
        MimeStreamParser parser = new MimeStreamParser();
        EmailContentHandler handler = new EmailContentHandler();
        parser.setContentHandler(handler);

        try {
            parser.parse(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            System.err.format("Failed to parse message \"%s\": %s\n",
                    getFirstLineMax(input, 80, "(empty)"), e.getMessage());
        }

        return handler.getResult();
    }

    /**
     * Content handler for mime4j mail parser.
     */
    private static class EmailContentHandler extends AbstractContentHandler {
        private Builder builder;

        private EmailContentHandler() {
            builder = new Builder();
        }

        private Email getResult() {
            return builder.build();
        }

        public void body(BodyDescriptor bodyDescriptor, InputStream inputStream) {
            String body = new Scanner(inputStream, bodyDescriptor.getCharset()).useDelimiter("\\Z").next();
            builder.body(body);
        }

        public void field(Field rawField) {
            if      (rawField.getName().equals("From"))        parseFrom(rawField.getBody());
            else if (rawField.getName().equals("To"))          builder.to(rawField.getBody());
            else if (rawField.getName().equals("Cc"))          builder.cc(rawField.getBody());
            else if (rawField.getName().equals("Date"))        builder.date(rawField.getBody());
            else if (rawField.getName().equals("Subject"))     builder.subject(rawField.getBody());
            else if (rawField.getName().equals("Message-ID"))  builder.messageId(rawField.getBody());
            else if (rawField.getName().equals("In-Reply-To")) builder.inReplyTo(rawField.getBody());
        }

        public void startMultipart(BodyDescriptor bodyDescriptor) {
            // ignore
        }

        private void parseFrom (String field) {
            // For this application, the from field format is "localpart at domain (name)".
            // FIXME This regex does the job for well-formed input, but should be replaced.
            Pattern p = Pattern.compile("(.*) at ((?:\\w+.)+\\w+) (?:\\((.*)\\))?");
            Matcher m = p.matcher(field);
            if (m.find()) {
                builder.fromAddress(m.group(1) + "@" + m.group(2));
                builder.fromName(m.groupCount() > 2 ? m.group(3) : "");
            }
        }
    }

    /**
     * Returns either the first line or a maximum of maxLen characters from the input.  Null-safe.
     *
     * @param input Input
     * @param maxLen Maximum length of original input to return
     * @param defIfEmpty Default to return if the string is empty or null.
     * @return Output string (non-newline-terminated)
     */
    private static String getFirstLineMax(String input, int maxLen, String defIfEmpty) {
        int len = Math.min(input.indexOf('\n'), maxLen);
        return len > 0 ? input.substring(0, len) : defIfEmpty;
    }

    /**
     * Retrieve a one-line summary of an email (subject, from address and name, date).
     *
     * @return Short email summary (non-newline-terminated)
     */
    public String getSummary() {
        return String.format("%s (%s (%s) %s)", subject, fromAddress, fromName, date);
    }

    @Override
    public String toString() {
        return new StringBuilder("Email{")
                .append("subject='").append(subject).append('\'')
                .append(", date='").append(date).append('\'')
                .append(", fromName='").append(fromName).append('\'')
                .append(", fromAddress='").append(fromAddress).append('\'')
                .append(", to='").append(to).append('\'')
                .append(", cc='").append(cc).append('\'')
                .append(", messageId='").append(messageId).append('\'')
                .append(", inReplyTo='").append(inReplyTo).append('\'')
                .append(", body='").append(body).append('\'')
                .append('}').toString();
    }

    public String getBody() {
        return body;
    }

    public String getCc() {
        return cc;
    }

    public String getDate() {
        return date;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public String getFromName() {
        return fromName;
    }

    public String getInReplyTo() {
        return inReplyTo;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSubject() {
        return subject;
    }

    public String getTo() {
        return to;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Email email = (Email) o;
        return Objects.equals(fromAddress, email.fromAddress) &&
                Objects.equals(fromName, email.fromName) &&
                Objects.equals(to, email.to) &&
                Objects.equals(cc, email.cc) &&
                Objects.equals(date, email.date) &&
                Objects.equals(subject, email.subject) &&
                Objects.equals(messageId, email.messageId) &&
                Objects.equals(inReplyTo, email.inReplyTo) &&
                Objects.equals(body, email.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromAddress, fromName, to, cc, date, subject, messageId, inReplyTo, body);
    }
}
