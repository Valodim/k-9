package com.fsck.k9.message;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.fsck.k9.Account.QuoteStyle;
import com.fsck.k9.Identity;
import com.fsck.k9.K9;
import com.fsck.k9.R;
import com.fsck.k9.activity.MessageReference;
import com.fsck.k9.activity.misc.Attachment;
import com.fsck.k9.crypto.OpenPgpApiException;
import com.fsck.k9.crypto.PgpData;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.EncryptionType;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.MimeBodyPart;
import com.fsck.k9.mail.internet.MimeHeader;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeMessageHelper;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mail.internet.TextBody;
import com.fsck.k9.mailstore.BinaryMemoryBody;
import com.fsck.k9.mailstore.TempFileBody;
import com.fsck.k9.mailstore.TempFileMessageBody;
import org.apache.james.mime4j.codec.EncoderUtil;
import org.apache.james.mime4j.util.MimeUtil;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.util.OpenPgpApi;


public class MessageBuilder {
    private final Context context;

    private String subject;
    private Address[] to;
    private Address[] cc;
    private Address[] bcc;
    private String inReplyTo;
    private String references;
    private boolean requestReadReceipt;
    private Identity identity;
    private SimpleMessageFormat messageFormat;
    private String text;
    private PgpData pgpData;
    private List<Attachment> attachments;
    private String signature;
    private QuoteStyle quoteStyle;
    private QuotedTextMode quotedTextMode;
    private String quotedText;
    private InsertableHtmlContent quotedHtmlContent;
    private boolean isReplyAfterQuote;
    private boolean isSignatureBeforeQuotedText;
    private boolean identityChanged;
    private boolean signatureChanged;
    private int cursorPosition;
    private MessageReference messageReference;
    private boolean isDraft;

    private Intent pgpMimeSignIntent = null;
    private Intent pgpMimeEncryptIntent = null;
    private OpenPgpApi openPgpApi;

    public MessageBuilder(Context context) {
        this.context = context;
    }

    /**
     * Build the final message to be sent (or saved). If there is another message quoted in this one, it will be baked
     * into the final message here.
     */
    public MimeMessage build() throws MessagingException, OpenPgpApiException {
        //FIXME: check arguments

        MimeMessage message = new MimeMessage();

        buildHeader(message);
        buildBody(message);

        if (pgpMimeSignIntent != null){
            encapsulateMimeInMultipartSigned(message);
        }
        if (pgpMimeEncryptIntent != null){
            encapsulateMimeInMultipartEncrypted(message);
        }
        return message;
    }

    private void buildHeader(MimeMessage message) throws MessagingException {
        message.addSentDate(new Date(), K9.hideTimeZone());
        Address from = new Address(identity.getEmail(), identity.getName());
        message.setFrom(from);
        message.setRecipients(RecipientType.TO, to);
        message.setRecipients(RecipientType.CC, cc);
        message.setRecipients(RecipientType.BCC, bcc);
        message.setSubject(subject);

        if (requestReadReceipt) {
            message.setHeader("Disposition-Notification-To", from.toEncodedString());
            message.setHeader("X-Confirm-Reading-To", from.toEncodedString());
            message.setHeader("Return-Receipt-To", from.toEncodedString());
        }

        if (!K9.hideUserAgent()) {
            message.setHeader("User-Agent", context.getString(R.string.message_header_mua));
        }

        final String replyTo = identity.getReplyTo();
        if (replyTo != null) {
            message.setReplyTo(new Address[] { new Address(replyTo) });
        }

        if (inReplyTo != null) {
            message.setInReplyTo(inReplyTo);
        }

        if (references != null) {
            message.setReferences(references);
        }

        message.generateMessageId();
    }

    private void buildBody(MimeMessage message) throws MessagingException {
        // Build the body.
        // TODO FIXME - body can be either an HTML or Text part, depending on whether we're in
        // HTML mode or not.  Should probably fix this so we don't mix up html and text parts.
        TextBody body;
        if (pgpData.getEncryptedData() != null) {
            String text = pgpData.getEncryptedData();
            body = new TextBody(text);
            message.setEncryptionType(EncryptionType.INLINE);
        } else {
            body = buildText(isDraft);
        }

        // text/plain part when messageFormat == MessageFormat.HTML
        TextBody bodyPlain = null;

        final boolean hasAttachments = !attachments.isEmpty();

        if (messageFormat == SimpleMessageFormat.HTML) {
            // HTML message (with alternative text part)

            // This is the compiled MIME part for an HTML message.
            MimeMultipart composedMimeMessage = new MimeMultipart();
            composedMimeMessage.setSubType("alternative");   // Let the receiver select either the text or the HTML part.
            composedMimeMessage.addBodyPart(new MimeBodyPart(body, "text/html"));
            bodyPlain = buildText(isDraft, SimpleMessageFormat.TEXT);
            composedMimeMessage.addBodyPart(new MimeBodyPart(bodyPlain, "text/plain"));

            if (hasAttachments) {
                // If we're HTML and have attachments, we have a MimeMultipart container to hold the
                // whole message (mp here), of which one part is a MimeMultipart container
                // (composedMimeMessage) with the user's composed messages, and subsequent parts for
                // the attachments.
                MimeMultipart mp = new MimeMultipart();
                mp.addBodyPart(new MimeBodyPart(composedMimeMessage));
                addAttachmentsToMessage(mp);
                MimeMessageHelper.setBody(message, mp);
            } else {
                // If no attachments, our multipart/alternative part is the only one we need.
                MimeMessageHelper.setBody(message, composedMimeMessage);
            }
        } else if (messageFormat == SimpleMessageFormat.TEXT) {
            // Text-only message.
            if (hasAttachments) {
                MimeMultipart mp = new MimeMultipart();
                mp.addBodyPart(new MimeBodyPart(body, "text/plain"));
                addAttachmentsToMessage(mp);
                MimeMessageHelper.setBody(message, mp);
            } else {
                // No attachments to include, just stick the text body in the message and call it good.
                MimeMessageHelper.setBody(message, body);
            }
        }

        // If this is a draft, add metadata for thawing.
        if (isDraft) {
            // Add the identity to the message.
            message.addHeader(K9.IDENTITY_HEADER, buildIdentityHeader(body, bodyPlain));
        }
    }

    public TextBody buildText() {
        return buildText(isDraft, messageFormat);
    }

    private String buildIdentityHeader(TextBody body, TextBody bodyPlain) {
        return new IdentityHeaderBuilder()
                .setCursorPosition(cursorPosition)
                .setIdentity(identity)
                .setIdentityChanged(identityChanged)
                .setMessageFormat(messageFormat)
                .setMessageReference(messageReference)
                .setQuotedHtmlContent(quotedHtmlContent)
                .setQuoteStyle(quoteStyle)
                .setQuoteTextMode(quotedTextMode)
                .setSignature(signature)
                .setSignatureChanged(signatureChanged)
                .setBody(body)
                .setBodyPlain(bodyPlain)
                .build();
    }

    /**
     * Add attachments as parts into a MimeMultipart container.
     * @param mp MimeMultipart container in which to insert parts.
     * @throws MessagingException
     */
    private void addAttachmentsToMessage(final MimeMultipart mp) throws MessagingException {
        Body body;
        for (Attachment attachment : attachments) {
            if (attachment.state != Attachment.LoadingState.COMPLETE) {
                continue;
            }

            String contentType = attachment.contentType;
            if (MimeUtil.isMessage(contentType)) {
                body = new TempFileMessageBody(attachment.filename);
            } else {
                body = new TempFileBody(attachment.filename);
            }
            MimeBodyPart bp = new MimeBodyPart(body);

            /*
             * Correctly encode the filename here. Otherwise the whole
             * header value (all parameters at once) will be encoded by
             * MimeHeader.writeTo().
             */
            bp.addHeader(MimeHeader.HEADER_CONTENT_TYPE, String.format("%s;\r\n name=\"%s\"",
                    contentType,
                    EncoderUtil.encodeIfNecessary(attachment.name,
                            EncoderUtil.Usage.WORD_ENTITY, 7)));

            bp.setEncoding(MimeUtility.getEncodingforType(contentType));

            /*
             * TODO: Oh the joys of MIME...
             *
             * From RFC 2183 (The Content-Disposition Header Field):
             * "Parameter values longer than 78 characters, or which
             *  contain non-ASCII characters, MUST be encoded as specified
             *  in [RFC 2184]."
             *
             * Example:
             *
             * Content-Type: application/x-stuff
             *  title*1*=us-ascii'en'This%20is%20even%20more%20
             *  title*2*=%2A%2A%2Afun%2A%2A%2A%20
             *  title*3="isn't it!"
             */
            bp.addHeader(MimeHeader.HEADER_CONTENT_DISPOSITION, String.format(Locale.US,
                    "attachment;\r\n filename=\"%s\";\r\n size=%d",
                    attachment.name, attachment.size));

            mp.addBodyPart(bp);
        }
    }

    /**
     * Build the Body that will contain the text of the message. We'll decide where to
     * include it later. Draft messages are treated somewhat differently in that signatures are not
     * appended and HTML separators between composed text and quoted text are not added.
     * @param isDraft If we should build a message that will be saved as a draft (as opposed to sent).
     */
    private TextBody buildText(boolean isDraft) {
        return buildText(isDraft, messageFormat);
    }

    /**
     * Build the {@link Body} that will contain the text of the message.
     *
     * <p>
     * Draft messages are treated somewhat differently in that signatures are not appended and HTML
     * separators between composed text and quoted text are not added.
     * </p>
     *
     * @param isDraft
     *         If {@code true} we build a message that will be saved as a draft (as opposed to
     *         sent).
     * @param simpleMessageFormat
     *         Specifies what type of message to build ({@code text/plain} vs. {@code text/html}).
     *
     * @return {@link TextBody} instance that contains the entered text and possibly the quoted
     *         original message.
     */
    private TextBody buildText(boolean isDraft, SimpleMessageFormat simpleMessageFormat) {
        String messageText = text;

        TextBodyBuilder textBodyBuilder = new TextBodyBuilder(messageText);

        /*
         * Find out if we need to include the original message as quoted text.
         *
         * We include the quoted text in the body if the user didn't choose to
         * hide it. We always include the quoted text when we're saving a draft.
         * That's so the user is able to "un-hide" the quoted text if (s)he
         * opens a saved draft.
         */
        boolean includeQuotedText = (isDraft || quotedTextMode == QuotedTextMode.SHOW);
        boolean isReplyAfterQuote = (quoteStyle == QuoteStyle.PREFIX && this.isReplyAfterQuote);

        textBodyBuilder.setIncludeQuotedText(false);
        if (includeQuotedText) {
            if (simpleMessageFormat == SimpleMessageFormat.HTML && quotedHtmlContent != null) {
                textBodyBuilder.setIncludeQuotedText(true);
                textBodyBuilder.setQuotedTextHtml(quotedHtmlContent);
                textBodyBuilder.setReplyAfterQuote(isReplyAfterQuote);
            }

            if (simpleMessageFormat == SimpleMessageFormat.TEXT && quotedText.length() > 0) {
                textBodyBuilder.setIncludeQuotedText(true);
                textBodyBuilder.setQuotedText(quotedText);
                textBodyBuilder.setReplyAfterQuote(isReplyAfterQuote);
            }
        }

        textBodyBuilder.setInsertSeparator(!isDraft);

        boolean useSignature = (!isDraft && identity.getSignatureUse());
        if (useSignature) {
            textBodyBuilder.setAppendSignature(true);
            textBodyBuilder.setSignature(signature);
            textBodyBuilder.setSignatureBeforeQuotedText(isSignatureBeforeQuotedText);
        } else {
            textBodyBuilder.setAppendSignature(false);
        }

        TextBody body;
        if (simpleMessageFormat == SimpleMessageFormat.HTML) {
            body = textBodyBuilder.buildTextHtml();
        } else {
            body = textBodyBuilder.buildTextPlain();
        }
        return body;
    }

    public MessageBuilder setSubject(String subject) {
        this.subject = subject;
        return this;
    }

    public MessageBuilder setTo(List<Address> to) {
        this.to = to.toArray(new Address[to.size()]);
        return this;
    }

    public MessageBuilder setTo(Address[] to) {
        this.to = to;
        return this;
    }

    public MessageBuilder setCc(Address[] cc) {
        this.cc = cc;
        return this;
    }

    public MessageBuilder setCc(List<Address> cc) {
        this.cc = cc.toArray(new Address[cc.size()]);
        return this;
    }

    public MessageBuilder setBcc(Address[] bcc) {
        this.bcc = bcc;
        return this;
    }

    public MessageBuilder setBcc(List<Address> bcc) {
        this.bcc = bcc.toArray(new Address[bcc.size()]);
        return this;
    }

    public MessageBuilder setInReplyTo(String inReplyTo) {
        this.inReplyTo = inReplyTo;
        return this;
    }

    public MessageBuilder setReferences(String references) {
        this.references = references;
        return this;
    }

    public MessageBuilder setRequestReadReceipt(boolean requestReadReceipt) {
        this.requestReadReceipt = requestReadReceipt;
        return this;
    }

    public MessageBuilder setIdentity(Identity identity) {
        this.identity = identity;
        return this;
    }

    public MessageBuilder setMessageFormat(SimpleMessageFormat messageFormat) {
        this.messageFormat = messageFormat;
        return this;
    }

    public MessageBuilder setText(String text) {
        this.text = text;
        return this;
    }

    public MessageBuilder setPgpData(PgpData pgpData) {
        this.pgpData = pgpData;
        return this;
    }

    public MessageBuilder setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
        return this;
    }

    public MessageBuilder setSignature(String signature) {
        this.signature = signature;
        return this;
    }

    public MessageBuilder setQuoteStyle(QuoteStyle quoteStyle) {
        this.quoteStyle = quoteStyle;
        return this;
    }

    public MessageBuilder setQuotedTextMode(QuotedTextMode quotedTextMode) {
        this.quotedTextMode = quotedTextMode;
        return this;
    }

    public MessageBuilder setQuotedText(String quotedText) {
        this.quotedText = quotedText;
        return this;
    }

    public MessageBuilder setQuotedHtmlContent(InsertableHtmlContent quotedHtmlContent) {
        this.quotedHtmlContent = quotedHtmlContent;
        return this;
    }

    public MessageBuilder setReplyAfterQuote(boolean isReplyAfterQuote) {
        this.isReplyAfterQuote = isReplyAfterQuote;
        return this;
    }

    public MessageBuilder setSignatureBeforeQuotedText(boolean isSignatureBeforeQuotedText) {
        this.isSignatureBeforeQuotedText = isSignatureBeforeQuotedText;
        return this;
    }

    public MessageBuilder setIdentityChanged(boolean identityChanged) {
        this.identityChanged = identityChanged;
        return this;
    }

    public MessageBuilder setSignatureChanged(boolean signatureChanged) {
        this.signatureChanged = signatureChanged;
        return this;
    }

    public MessageBuilder setCursorPosition(int cursorPosition) {
        this.cursorPosition = cursorPosition;
        return this;
    }

    public MessageBuilder setMessageReference(MessageReference messageReference) {
        this.messageReference = messageReference;
        return this;
    }

    public MessageBuilder setDraft(boolean isDraft) {
        this.isDraft = isDraft;
        return this;
    }

    /**
     * Turns PGP/MIME encryption on.
     * @param encrypt turn encryption on, if false other parameters will be ignored
     * @param encryptIntent Intent to use to perform the encryption. This Intent must be
     *                      fully functional and mustn't requires further interaction. If it isn't the case,
     *                      message building will fail.
     * @param api Link to an initialized OpenPgpApi
     * @return this
     */
    public MessageBuilder setPgpMimeEncryption(boolean encrypt, Intent encryptIntent, OpenPgpApi api){
        if (encrypt) {
            this.pgpMimeEncryptIntent = encryptIntent;
            this.openPgpApi = api;
        } else {
            pgpMimeEncryptIntent = null;
        }
        return this;
    }

    /**
     * Turns PGP/MIME signature on.
     * @param sign turn on signature, if false other parameters will be ignored
     * @param signIntent Intent to use to sign the message. This Intent must be
     *                      fully functional and mustn't requires further interaction. If it isn't the case,
     *                      message building will fail.
     * @param api Link to an initialized OpenPgpApi
     * @return this
     */
    public MessageBuilder setPgpMimeSignature(boolean sign, Intent signIntent, OpenPgpApi api){
        if (sign) {
            this.pgpMimeSignIntent = signIntent;
            this.openPgpApi = api;
        } else {
            this.pgpMimeSignIntent = null;
        }
        return this;
    }

    /**
     * Process the message in order to encapsulate it in a multipart/signed message.
     * @see <a href="http://tools.ietf.org/html/rfc2015">RFC-2015</a>
     * @param mime messsage to process
     * @throws MessagingException
     */
    private void encapsulateMimeInMultipartSigned(MimeMessage mime) throws MessagingException, OpenPgpApiException {
        /*
         * Once set to true, text messages will be sign safe (RFC-3156 §3) until K9Mail is stopped.
         * This "global parameter" is made to avoid a double generation (regular/sign safe) on the
         * whole Part/Body hierarchy and still limit the scope of this extra encoding to pgp/mime users
         * Downside it that even unsigned messages will contain extra-encoding once a message has
         * been signed. But it will be transparent if the recipient decodes properly quoted-printable.
         */
        TextBody.setSignSafe(true);
        MimeBodyPart bodyPart = mime.toBodyPart();
        bodyPart.setUsing7bitTransport();
        ByteArrayOutputStream messageToSign = new ByteArrayOutputStream();
        try {
            bodyPart.writeTo(messageToSign);
            Intent result = openPgpApi.executeApi(pgpMimeSignIntent, new ByteArrayInputStream(messageToSign.toByteArray()), null);
            if (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) != OpenPgpApi.RESULT_CODE_SUCCESS) {
                OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                Log.e(OpenPgpApi.TAG, error.getMessage());
                throw new OpenPgpApiException(error);
            }
            byte[] signedData = result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE);

            MimeMultipart multipartSigned = new MimeMultipart();
            multipartSigned.setSubType("signed");
            multipartSigned.addBodyPart(bodyPart);
            multipartSigned.addBodyPart(new MimeBodyPart(new BinaryMemoryBody(signedData, MimeUtil.ENC_7BIT), "application/pgp-signature"));

            MimeMessageHelper.setBody(mime, multipartSigned);
            mime.addContentTypeParameter("protocol", "\"application/pgp-signature\"");
            mime.addContentTypeParameter("micalg", "pgp-sha256");
        } catch(IOException e){
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * Process the message in order to encapsulate it in a multipart/encrypted mime entity
     * @see <a href="http://tools.ietf.org/html/rfc2015">RFC-2015</a>
     * @param mime message to encrypt and process
     * @throws MessagingException
     */
    private void encapsulateMimeInMultipartEncrypted(MimeMessage mime) throws MessagingException, OpenPgpApiException {
        ByteArrayOutputStream mimeMessageToEncrypt = new ByteArrayOutputStream();
        try {
            mime.toBodyPart().writeTo(mimeMessageToEncrypt);

            final ByteArrayOutputStream encryptedData = new ByteArrayOutputStream();
            Intent result = openPgpApi.executeApi(pgpMimeEncryptIntent, new ByteArrayInputStream(mimeMessageToEncrypt.toByteArray()), encryptedData);
            if (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) == OpenPgpApi.RESULT_CODE_ERROR) {
                OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                Log.e(OpenPgpApi.TAG, error.getMessage());
                throw new OpenPgpApiException(error);
            }

            MimeMultipart multipartEncrypted = new MimeMultipart();
            multipartEncrypted.setSubType("encrypted");
            multipartEncrypted.addBodyPart(new MimeBodyPart(new BinaryMemoryBody("Version: 1".getBytes("US-ASCII"), MimeUtil.ENC_7BIT), "application/pgp-encrypted"));

            multipartEncrypted.addBodyPart(new MimeBodyPart(
                    new BinaryMemoryBody(encryptedData.toByteArray(), MimeUtil.ENC_7BIT), "application/octet-stream"));
            MimeMessageHelper.setBody(mime, multipartEncrypted);
            mime.addContentTypeParameter("protocol", "\"application/pgp-encrypted\"");
            mime.setEncryptionType(EncryptionType.PGP_MIME);
        } catch (IOException e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }
}
