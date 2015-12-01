package com.fsck.k9.activity;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;

import com.fsck.k9.Account;
import com.fsck.k9.Identity;
import com.fsck.k9.R;
import com.fsck.k9.activity.MessageCompose.CaseInsensitiveParamWrapper;
import com.fsck.k9.activity.RecipientSelectView.Recipient;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalMessage;


public class RecipientPresenter {

    private static final String STATE_KEY_CC_SHOWN =
            "com.fsck.k9.activity.MessageCompose.ccShown";
    private static final String STATE_KEY_BCC_SHOWN =
            "com.fsck.k9.activity.MessageCompose.bccShown";

    private Context context;
    private RecipientView recipientView;
    private Account account;
    private String cryptoProvider;

    public RecipientPresenter(Context context, RecipientView recipientView, Account account) {
        this.recipientView = recipientView;
        this.context = context;
        recipientView.setPresenter(this);
        onSwitchAccount(account);
    }

    public List<Address> getToAddresses() {
        return recipientView.getToAddresses();
    }

    public List<Address> getCcAddresses() {
        return recipientView.getCcAddresses();
    }

    public List<Address> getBccAddresses() {
        return recipientView.getBccAddresses();
    }

    public List<Recipient> getAllRecipients() {
        ArrayList<Recipient> result = new ArrayList<Recipient>();

        result.addAll(recipientView.getToRecipients());
        result.addAll(recipientView.getCcRecipients());
        result.addAll(recipientView.getBccRecipients());

        return result;
    }

    public List<Address> getAllRecipientAddresses() {
        ArrayList<Address> result = new ArrayList<Address>();

        result.addAll(getToAddresses());
        result.addAll(getCcAddresses());
        result.addAll(getBccAddresses());

        return result;
    }

    public boolean checkHasNoRecipients() {
        if (getToAddresses().isEmpty() && getCcAddresses().isEmpty() && getBccAddresses().isEmpty()) {
            recipientView.showNoRecipientsError();
            return true;
        }
        return false;
    }

    public void initFromReplyToMessage(Message message) {
        Address[] replyToAddresses;
        if (message.getReplyTo().length > 0) {
            replyToAddresses = message.getReplyTo();
        } else {
            replyToAddresses = message.getFrom();
        }

        try {
            // if we're replying to a message we sent, we probably meant
            // to reply to the recipient of that message
            if (account.isAnIdentity(replyToAddresses)) {
                replyToAddresses = message.getRecipients(RecipientType.TO);
            }

            addRecipientsFromAddresses(RecipientType.TO, replyToAddresses);

            if (message.getReplyTo().length > 0) {
                for (Address address : message.getFrom()) {
                    if (!account.isAnIdentity(address) && !Utility.arrayContains(replyToAddresses, address)) {
                        addRecipientsFromAddresses(RecipientType.TO, address);
                    }
                }
            }
            for (Address address : message.getRecipients(RecipientType.TO)) {
                if (!account.isAnIdentity(address) && !Utility.arrayContains(replyToAddresses, address)) {
                    addToAddresses(address);
                }

            }
            if (message.getRecipients(RecipientType.CC).length > 0) {
                for (Address address : message.getRecipients(RecipientType.CC)) {
                    if (!account.isAnIdentity(address) && !Utility.arrayContains(replyToAddresses, address)) {
                        addCcAddresses(address);
                    }

                }
            }
        } catch (MessagingException e) {
            // can't happen, we know the recipient types exist
            throw new AssertionError(e);
        }
    }

    public void initFromMailto(Uri mailtoUri, CaseInsensitiveParamWrapper uri) {
        String schemaSpecific = mailtoUri.getSchemeSpecificPart();
        int end = schemaSpecific.indexOf('?');
        if (end == -1) {
            end = schemaSpecific.length();
        }

        // Extract the recipient's email address from the mailto URI if there's one.
        String recipient = Uri.decode(schemaSpecific.substring(0, end));

        // Read additional recipients from the "to" parameter.
        List<String> to = uri.getQueryParameters("to");
        if (recipient.length() != 0) {
            to = new ArrayList<String>(to);
            to.add(0, recipient);
        }
        addToAddresses(addressFromStringArray(to));

        // Read carbon copy recipients from the "cc" parameter.
        addCcAddresses(addressFromStringArray(uri.getQueryParameters("cc")));

        // Read blind carbon copy recipients from the "bcc" parameter.
        addBccAddresses(addressFromStringArray(uri.getQueryParameters("bcc")));
    }

    public void initFromSendOrViewIntent(Intent intent) {
        String[] extraEmail = intent.getStringArrayExtra(Intent.EXTRA_EMAIL);
        String[] extraCc = intent.getStringArrayExtra(Intent.EXTRA_CC);
        String[] extraBcc = intent.getStringArrayExtra(Intent.EXTRA_BCC);

        if (extraEmail != null) {
            addToAddresses(addressFromStringArray(extraEmail));
        }

        if (extraCc != null) {
            addCcAddresses(addressFromStringArray(extraCc));
        }

        if (extraBcc != null) {
            addBccAddresses(addressFromStringArray(extraBcc));
        }
    }

    public void onRestoreInstanceState(Bundle savedInstanceState) {
        recipientView.setCcVisibility(savedInstanceState.getBoolean(STATE_KEY_CC_SHOWN));
        recipientView.setBccVisibility(savedInstanceState.getBoolean(STATE_KEY_BCC_SHOWN));
        recipientView.invalidateOptionsMenu();
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_KEY_CC_SHOWN, recipientView.isCcVisible());
        outState.putBoolean(STATE_KEY_BCC_SHOWN, recipientView.isBccVisible());
    }

    public void initFromDraftMessage(LocalMessage message) {
        try {
            addToAddresses(message.getRecipients(RecipientType.TO));

            Address[] ccRecipients = message.getRecipients(RecipientType.CC);
            addCcAddresses(ccRecipients);

            Address[] bccRecipients = message.getRecipients(RecipientType.BCC);
            addBccAddresses(bccRecipients);
        } catch (MessagingException e) {
            // can't happen, we know the recipient types exist
            throw new AssertionError(e);
        }
    }

    void addToAddresses(Address... toAddresses) {
        addRecipientsFromAddresses(RecipientType.TO, toAddresses);
    }

    void addCcAddresses(Address... ccAddresses) {
        if (ccAddresses.length > 0) {
            addRecipientsFromAddresses(RecipientType.CC, ccAddresses);
            recipientView.setCcVisibility(true);
            recipientView.invalidateOptionsMenu();
        }
    }

    void addBccAddresses(Address... bccRecipients) {
        if (bccRecipients.length > 0) {
            addRecipientsFromAddresses(RecipientType.BCC, bccRecipients);
            String bccAddress = account.getAlwaysBcc();

            // If the auto-bcc is the only entry in the BCC list, don't show the Bcc fields.
            boolean alreadyVisible = recipientView.isBccVisible();
            boolean singleBccRecipientFromAccount =
                    bccRecipients.length == 1 && bccRecipients[0].toString().equals(bccAddress);
            recipientView.setBccVisibility(alreadyVisible || singleBccRecipientFromAccount);
            recipientView.invalidateOptionsMenu();
        }
    }

    public void onPrepareOptionsMenu(Menu menu) {
        if (recipientView.isCcVisible() && recipientView.isBccVisible()) {
            menu.findItem(R.id.add_cc_bcc).setVisible(false);
        }
    }

    public void onSwitchAccount(Account account) {
        this.account = account;
        if (account.isAlwaysShowCcBcc()) {
            recipientView.setCcVisibility(true);
            recipientView.setBccVisibility(true);
            recipientView.invalidateOptionsMenu();
        }
        cryptoProvider = account.getOpenPgpProvider();
        recipientView.setCryptoProvider(cryptoProvider);
    }

    @SuppressWarnings("UnusedParameters")
    public void onSwitchIdentity(Identity identity) {

        // TODO decide what actually to do on identity switch?
        /*
        if (mIdentityChanged) {
            mBccWrapper.setVisibility(View.VISIBLE);
        }
        mBccView.setText("");
        mBccView.addAddress(new Address(mAccount.getAlwaysBcc(), ""));
        */

    }

    private static Address[] addressFromStringArray(String[] addresses) {
        return addressFromStringArray(Arrays.asList(addresses));
    }

    private static Address[] addressFromStringArray(List<String> addresses) {
        ArrayList<Address> result = new ArrayList<Address>(addresses.size());

        for (String addressStr : addresses) {
            Collections.addAll(result, Address.parseUnencoded(addressStr));
        }

        return result.toArray(new Address[result.size()]);
    }

    public void onMenuShowCcBcc() {
        recipientView.setCcVisibility(true);
        recipientView.setBccVisibility(true);
        recipientView.invalidateOptionsMenu();
    }

    public void updateCryptoStatus() {
        List<Recipient> recipients = getAllRecipients();
        if (recipients.isEmpty()) {
            recipientView.hideCryptoStatus();
            return;
        }

        boolean allKeys = true, allVerified = true;
        for (Recipient recipient : recipients) {
            int cryptoStatus = recipient.getCryptoStatus();
            if (cryptoStatus == 0) {
                allKeys = false;
            } else if (cryptoStatus == 1) {
                allVerified = false;
            }
        }

        recipientView.showCryptoStatus(allKeys, allVerified);
    }

    @SuppressWarnings("UnusedParameters")
    public void onToTokenAdded(Recipient recipient) {
        updateCryptoStatus();
    }
    @SuppressWarnings("UnusedParameters")
    public void onToTokenRemoved(Recipient recipient) {
        updateCryptoStatus();
    }

    @SuppressWarnings("UnusedParameters")
    public void onCcTokenAdded(Recipient recipient) {
        updateCryptoStatus();
    }
    @SuppressWarnings("UnusedParameters")
    public void onCcTokenRemoved(Recipient recipient) {
        updateCryptoStatus();
    }

    @SuppressWarnings("UnusedParameters")
    public void onBccTokenAdded(Recipient recipient) {
        updateCryptoStatus();
    }
    @SuppressWarnings("UnusedParameters")
    public void onBccTokenRemoved(Recipient recipient) {
        updateCryptoStatus();
    }

    private void addRecipientsFromAddresses(final RecipientType type, final Address... addresses) {
        new RecipientLoader(context, cryptoProvider, addresses) {
            @Override
            public void deliverResult(List<Recipient> result) {
                Recipient[] recipientArray = result.toArray(new Recipient[result.size()]);
                switch (type) {
                    case TO:
                        recipientView.addToRecipients(recipientArray);
                        break;
                    case CC:
                        recipientView.addCcRecipients(recipientArray);
                        break;
                    case BCC:
                        recipientView.addBccRecipients(recipientArray);
                        break;
                }
                stopLoading();
            }
        }.startLoading();
    }

}
