 /*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.data;

import java.util.List;

import com.android.mms.MmsConfig;
import com.android.mms.ExceedMessageSizeException;
import com.android.mms.ResolutionException;
import com.android.mms.UnsupportContentTypeException;
import com.android.mms.LogTag;
import com.android.mms.model.AudioModel;
import com.android.mms.model.ImageModel;
import com.android.mms.model.MediaModel;
import com.android.mms.model.SlideModel;
import com.android.mms.model.SlideshowModel;
import com.android.mms.model.TextModel;
import com.android.mms.model.VideoModel;
import com.android.mms.transaction.MessageSender;
import com.android.mms.transaction.MmsMessageSender;
import com.android.mms.util.Recycler;
import com.android.mms.transaction.SmsMessageSender;
import com.android.mms.ui.ComposeMessageActivity;
import com.android.mms.ui.MessageUtils;
import com.android.mms.ui.SlideshowEditor;
import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.EncodedStringValue;
import com.google.android.mms.pdu.PduBody;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.SendReq;
import com.google.android.mms.util.SqliteWrapper;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import android.util.Log;

/**
 * Contains all state related to a message being edited by the user.
 */
public class WorkingMessage {
    private static final String TAG = "WorkingMessage";
    private static final boolean DEBUG = false;

    // Database access stuff
    private final Context mContext;
    private final ContentResolver mContentResolver;

    // States that can require us to save or send a message as MMS.
    private static final int RECIPIENTS_REQUIRE_MMS = (1 << 0);     // 1
    private static final int HAS_SUBJECT = (1 << 1);                // 2
    private static final int HAS_ATTACHMENT = (1 << 2);             // 4
    private static final int LENGTH_REQUIRES_MMS = (1 << 3);        // 8
    private static final int FORCE_MMS = (1 << 4);                  // 16

    // A bitmap of the above indicating different properties of the message;
    // any bit set will require the message to be sent via MMS.
    private int mMmsState;

    // Errors from setAttachment()
    public static final int OK = 0;
    public static final int UNKNOWN_ERROR = -1;
    public static final int MESSAGE_SIZE_EXCEEDED = -2;
    public static final int UNSUPPORTED_TYPE = -3;
    public static final int IMAGE_TOO_LARGE = -4;

    // Attachment types
    public static final int TEXT = 0;
    public static final int IMAGE = 1;
    public static final int VIDEO = 2;
    public static final int AUDIO = 3;
    public static final int SLIDESHOW = 4;

    // Current attachment type of the message; one of the above values.
    private int mAttachmentType;

    // Conversation this message is targeting.
    private Conversation mConversation;

    // Text of the message.
    private CharSequence mText;
    // Slideshow for this message, if applicable.  If it's a simple attachment,
    // i.e. not SLIDESHOW, it will contain only one slide.
    private SlideshowModel mSlideshow;
    // Data URI of an MMS message if we have had to save it.
    private Uri mMessageUri;
    // MMS subject line for this message
    private CharSequence mSubject;

    // Set to true if this message has been discarded.
    private boolean mDiscarded = false;

    // Our callback interface
    private final MessageStatusListener mStatusListener;
    private List<String> mWorkingRecipients;

    // Message sizes in Outbox
    private static final String[] MMS_OUTBOX_PROJECTION = {
        Mms._ID,            // 0
        Mms.MESSAGE_SIZE    // 1
    };

    private static final int MMS_MESSAGE_SIZE_INDEX  = 1;


    /**
     * Callback interface for communicating important state changes back to
     * ComposeMessageActivity.
     */
    public interface MessageStatusListener {
        /**
         * Called when the protocol for sending the message changes from SMS
         * to MMS, and vice versa.
         *
         * @param mms If true, it changed to MMS.  If false, to SMS.
         */
        void onProtocolChanged(boolean mms);

        /**
         * Called when an attachment on the message has changed.
         */
        void onAttachmentChanged();

        /**
         * Called just before the process of sending a message.
         */
        void onPreMessageSent();

        /**
         * Called once the process of sending a message, triggered by
         * {@link send} has completed. This doesn't mean the send succeeded,
         * just that it has been dispatched to the network.
         */
        void onMessageSent();

        /**
         * Called if there are too many unsent messages in the queue and we're not allowing
         * any more Mms's to be sent.
         */
        void onMaxPendingMessagesReached();
    }

    private WorkingMessage(ComposeMessageActivity activity) {
        mContext = activity;
        mContentResolver = mContext.getContentResolver();
        mStatusListener = activity;
        mAttachmentType = TEXT;
        mText = "";
    }

    /**
     * Creates a new working message.
     */
    public static WorkingMessage createEmpty(ComposeMessageActivity activity) {
        // Make a new empty working message.
        WorkingMessage msg = new WorkingMessage(activity);
        return msg;
    }

    /**
     * Create a new WorkingMessage from the specified data URI, which typically
     * contains an MMS message.
     */
    public static WorkingMessage load(ComposeMessageActivity activity, Uri uri) {
        // If the message is not already in the draft box, move it there.
        if (!uri.toString().startsWith(Mms.Draft.CONTENT_URI.toString())) {
            PduPersister persister = PduPersister.getPduPersister(activity);
            if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                LogTag.debug("load: moving %s to drafts", uri);
            }
            try {
                uri = persister.move(uri, Mms.Draft.CONTENT_URI);
            } catch (MmsException e) {
                LogTag.error("Can't move %s to drafts", uri);
                return null;
            }
        }

        WorkingMessage msg = new WorkingMessage(activity);
        if (msg.loadFromUri(uri)) {
            return msg;
        }

        return null;
    }

    private void correctAttachmentState() {
        int slideCount = mSlideshow.size();

        // If we get an empty slideshow, tear down all MMS
        // state and discard the unnecessary message Uri.
        if (slideCount == 0) {
            mAttachmentType = TEXT;
            mSlideshow = null;
            asyncDelete(mMessageUri, null, null);
            mMessageUri = null;
        } else if (slideCount > 1) {
            mAttachmentType = SLIDESHOW;
        } else {
            SlideModel slide = mSlideshow.get(0);
            if (slide.hasImage()) {
                mAttachmentType = IMAGE;
            } else if (slide.hasVideo()) {
                mAttachmentType = VIDEO;
            } else if (slide.hasAudio()) {
                mAttachmentType = AUDIO;
            }
        }

        updateState(HAS_ATTACHMENT, hasAttachment(), false);
    }

    private boolean loadFromUri(Uri uri) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) LogTag.debug("loadFromUri %s", uri);
        try {
            mSlideshow = SlideshowModel.createFromMessageUri(mContext, uri);
        } catch (MmsException e) {
            LogTag.error("Couldn't load URI %s", uri);
            return false;
        }

        mMessageUri = uri;

        // Make sure all our state is as expected.
        syncTextFromSlideshow();
        correctAttachmentState();

        return true;
    }

    /**
     * Load the draft message for the specified conversation, or a new empty message if
     * none exists.
     */
    public static WorkingMessage loadDraft(ComposeMessageActivity activity,
                                           Conversation conv) {
        WorkingMessage msg = new WorkingMessage(activity);
        if (msg.loadFromConversation(conv)) {
            return msg;
        } else {
            return createEmpty(activity);
        }
    }

    private boolean loadFromConversation(Conversation conv) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) LogTag.debug("loadFromConversation %s", conv);

        long threadId = conv.getThreadId();
        if (threadId <= 0) {
            return false;
        }

        // Look for an SMS draft first.
        mText = readDraftSmsMessage(mContext, threadId, conv);
        if (!TextUtils.isEmpty(mText)) {
            return true;
        }

        // Then look for an MMS draft.
        StringBuilder sb = new StringBuilder();
        Uri uri = readDraftMmsMessage(mContext, threadId, sb);
        if (uri != null) {
            if (loadFromUri(uri)) {
                // If there was an MMS message, readDraftMmsMessage
                // will put the subject in our supplied StringBuilder.
                if (sb.length() > 0) {
                    setSubject(sb.toString(), false);
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Sets the text of the message to the specified CharSequence.
     */
    public void setText(CharSequence s) {
        mText = s;
    }

    /**
     * Returns the current message text.
     */
    public CharSequence getText() {
        return mText;
    }

    /**
     * Returns true if the message has any text.
     * @return
     */
    public boolean hasText() {
        return !TextUtils.isEmpty(mText);
    }

    /**
     * Adds an attachment to the message, replacing an old one if it existed.
     * @param type Type of this attachment, such as {@link IMAGE}
     * @param dataUri Uri containing the attachment data (or null for {@link TEXT})
     * @param append true if we should add the attachment to a new slide
     * @return An error code such as {@link UNKNOWN_ERROR} or {@link OK} if successful
     */
    public int setAttachment(int type, Uri dataUri, boolean append) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("setAttachment type=%d uri %s", type, dataUri);
        }
        int result = OK;

        // Make sure mSlideshow is set up and has a slide.
        ensureSlideshow();

        // Change the attachment and translate the various underlying
        // exceptions into useful error codes.
        try {
            if (append) {
                appendMedia(type, dataUri);
            } else {
                changeMedia(type, dataUri);
            }
        } catch (MmsException e) {
            result = UNKNOWN_ERROR;
        } catch (UnsupportContentTypeException e) {
            result = UNSUPPORTED_TYPE;
        } catch (ExceedMessageSizeException e) {
            result = MESSAGE_SIZE_EXCEEDED;
        } catch (ResolutionException e) {
            result = IMAGE_TOO_LARGE;
        }

        // If we were successful, update mAttachmentType and notify
        // the listener than there was a change.
        if (result == OK) {
            mAttachmentType = type;
            mStatusListener.onAttachmentChanged();
        } else if (append) {
            // We added a new slide and what we attempted to insert on the slide failed.
            // Delete that slide, otherwise we could end up with a bunch of blank slides.
            SlideshowEditor slideShowEditor = new SlideshowEditor(mContext, mSlideshow);
            slideShowEditor.removeSlide(mSlideshow.size() - 1);
        }

        // Set HAS_ATTACHMENT if we need it.
        updateState(HAS_ATTACHMENT, hasAttachment(), true);

        return result;
    }

    /**
     * Returns true if this message contains anything worth saving.
     */
    public boolean isWorthSaving() {
        // If it actually contains anything, it's of course not empty.
        if (hasText() || hasSubject() || hasAttachment() || hasSlideshow()) {
            return true;
        }

        // When saveAsMms() has been called, we set FORCE_MMS to represent
        // sort of an "invisible attachment" so that the message isn't thrown
        // away when we are shipping it off to other activities.
        if ((mMmsState & FORCE_MMS) > 0) {
            return true;
        }

        return false;
    }

    /**
     * Makes sure mSlideshow is set up.
     */
    private void ensureSlideshow() {
        if (mSlideshow != null) {
            return;
        }

        SlideshowModel slideshow = SlideshowModel.createNew(mContext);
        SlideModel slide = new SlideModel(slideshow);
        slideshow.add(slide);

        mSlideshow = slideshow;
    }

    /**
     * Change the message's attachment to the data in the specified Uri.
     * Used only for single-slide ("attachment mode") messages.
     */
    private void changeMedia(int type, Uri uri) throws MmsException {
        SlideModel slide = mSlideshow.get(0);
        MediaModel media;

        // Remove any previous attachments.
        slide.removeImage();
        slide.removeVideo();
        slide.removeAudio();

        // If we're changing to text, just bail out.
        if (type == TEXT) {
            return;
        }

        // Make a correct MediaModel for the type of attachment.
        if (type == IMAGE) {
            media = new ImageModel(mContext, uri, mSlideshow.getLayout().getImageRegion());
        } else if (type == VIDEO) {
            media = new VideoModel(mContext, uri, mSlideshow.getLayout().getImageRegion());
        } else if (type == AUDIO) {
            media = new AudioModel(mContext, uri);
        } else {
            throw new IllegalArgumentException("changeMedia type=" + type + ", uri=" + uri);
        }

        // Add it to the slide.
        slide.add(media);

        // For video and audio, set the duration of the slide to
        // that of the attachment.
        if (type == VIDEO || type == AUDIO) {
            slide.updateDuration(media.getDuration());
        }
    }

    /**
     * Add the message's attachment to the data in the specified Uri to a new slide.
     */
    private void appendMedia(int type, Uri uri) throws MmsException {

        // If we're changing to text, just bail out.
        if (type == TEXT) {
            return;
        }

        // The first time this method is called, mSlideshow.size() is going to be
        // one (a newly initialized slideshow has one empty slide). The first time we
        // attach the picture/video to that first empty slide. From then on when this
        // function is called, we've got to create a new slide and add the picture/video
        // to that new slide.
        boolean addNewSlide = true;
        if (mSlideshow.size() == 1 && !mSlideshow.isSimple()) {
            addNewSlide = false;
        }
        if (addNewSlide) {
            SlideshowEditor slideShowEditor = new SlideshowEditor(mContext, mSlideshow);
            if (!slideShowEditor.addNewSlide()) {
                return;
            }
        }
        // Make a correct MediaModel for the type of attachment.
        MediaModel media;
        SlideModel slide = mSlideshow.get(mSlideshow.size() - 1);
        if (type == IMAGE) {
            media = new ImageModel(mContext, uri, mSlideshow.getLayout().getImageRegion());
        } else if (type == VIDEO) {
            media = new VideoModel(mContext, uri, mSlideshow.getLayout().getImageRegion());
        } else if (type == AUDIO) {
            media = new AudioModel(mContext, uri);
        } else {
            throw new IllegalArgumentException("changeMedia type=" + type + ", uri=" + uri);
        }

        // Add it to the slide.
        slide.add(media);

        // For video and audio, set the duration of the slide to
        // that of the attachment.
        if (type == VIDEO || type == AUDIO) {
            slide.updateDuration(media.getDuration());
        }
    }

    /**
     * Returns true if the message has an attachment (including slideshows).
     */
    public boolean hasAttachment() {
        return (mAttachmentType > TEXT);
    }

    /**
     * Returns the slideshow associated with this message.
     */
    public SlideshowModel getSlideshow() {
        return mSlideshow;
    }

    /**
     * Returns true if the message has a real slideshow, as opposed to just
     * one image attachment, for example.
     */
    public boolean hasSlideshow() {
        return (mAttachmentType == SLIDESHOW);
    }

    /**
     * Sets the MMS subject of the message.  Passing null indicates that there
     * is no subject.  Passing "" will result in an empty subject being added
     * to the message, possibly triggering a conversion to MMS.  This extra
     * bit of state is needed to support ComposeMessageActivity converting to
     * MMS when the user adds a subject.  An empty subject will be removed
     * before saving to disk or sending, however.
     */
    public void setSubject(CharSequence s, boolean notify) {
        mSubject = s;
        updateState(HAS_SUBJECT, (s != null), notify);
    }

    /**
     * Returns the MMS subject of the message.
     */
    public CharSequence getSubject() {
        return mSubject;
    }

    /**
     * Returns true if this message has an MMS subject.
     * @return
     */
    public boolean hasSubject() {
        return !TextUtils.isEmpty(mSubject);
    }

    /**
     * Moves the message text into the slideshow.  Should be called any time
     * the message is about to be sent or written to disk.
     */
    private void syncTextToSlideshow() {
        if (mSlideshow == null || mSlideshow.size() != 1)
            return;

        SlideModel slide = mSlideshow.get(0);
        TextModel text;
        if (!slide.hasText()) {
            // Add a TextModel to slide 0 if one doesn't already exist
            text = new TextModel(mContext, ContentType.TEXT_PLAIN, "text_0.txt",
                                           mSlideshow.getLayout().getTextRegion());
            slide.add(text);
        } else {
            // Otherwise just reuse the existing one.
            text = slide.getText();
        }
        text.setText(mText);
    }

    /**
     * Sets the message text out of the slideshow.  Should be called any time
     * a slideshow is loaded from disk.
     */
    private void syncTextFromSlideshow() {
        // Don't sync text for real slideshows.
        if (mSlideshow.size() != 1) {
            return;
        }

        SlideModel slide = mSlideshow.get(0);
        if (!slide.hasText()) {
            return;
        }

        mText = slide.getText().getText();
    }

    /**
     * Removes the subject if it is empty, possibly converting back to SMS.
     */
    private void removeSubjectIfEmpty(boolean notify) {
        if (!hasSubject()) {
            setSubject(null, notify);
        }
    }

    /**
     * Gets internal message state ready for storage.  Should be called any
     * time the message is about to be sent or written to disk.
     */
    private void prepareForSave(boolean notify) {
        // Make sure our working set of recipients is resolved
        // to first-class Contact objects before we save.
        syncWorkingRecipients();

        if (requiresMms()) {
            ensureSlideshow();
            syncTextToSlideshow();
            removeSubjectIfEmpty(notify);
        }
    }

    /**
     * Resolve the temporary working set of recipients to a ContactList.
     */
    public void syncWorkingRecipients() {
        if (mWorkingRecipients != null) {
            ContactList recipients = ContactList.getByNumbers(mWorkingRecipients, false);
            mConversation.setRecipients(recipients);
            mWorkingRecipients = null;
        }
    }


    /**
     * Force the message to be saved as MMS and return the Uri of the message.
     * Typically used when handing a message off to another activity.
     */
    public Uri saveAsMms() {
        if (DEBUG) LogTag.debug("save mConversation=%s", mConversation);

        if (mDiscarded) {
            throw new IllegalStateException("save() called after discard()");
        }

        // FORCE_MMS behaves as sort of an "invisible attachment", making
        // the message seem non-empty (and thus not discarded).  This bit
        // is sticky until the last other MMS bit is removed, at which
        // point the message will fall back to SMS.
        updateState(FORCE_MMS, true, false);

        // Collect our state to be written to disk.
        prepareForSave(true /* notify */);

        // Make sure we are saving to the correct thread ID.
        mConversation.ensureThreadId();
        mConversation.setDraftState(true);

        PduPersister persister = PduPersister.getPduPersister(mContext);
        SendReq sendReq = makeSendReq(mConversation, mSubject);

        // If we don't already have a Uri lying around, make a new one.  If we do
        // have one already, make sure it is synced to disk.
        if (mMessageUri == null) {
            mMessageUri = createDraftMmsMessage(persister, sendReq, mSlideshow);
        } else {
            updateDraftMmsMessage(mMessageUri, persister, mSlideshow, sendReq);
        }

        return mMessageUri;
    }

    /**
     * Save this message as a draft in the conversation previously specified
     * to {@link setConversation}.
     */
    public void saveDraft() {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("saveDraft");
        }

        // If we have discarded the message, just bail out.
        if (mDiscarded) {
            return;
        }

        // Make sure setConversation was called.
        if (mConversation == null) {
            throw new IllegalStateException("saveDraft() called with no conversation");
        }

        // Get ready to write to disk. But don't notify message status when saving draft
        prepareForSave(false /* notify */);

        if (requiresMms()) {
            asyncUpdateDraftMmsMessage(mConversation);
        } else {
            String content = mText.toString();

            // bug 2169583: don't bother creating a thread id only to delete the thread
            // because the content is empty. When we delete the thread in updateDraftSmsMessage,
            // we didn't nullify conv.mThreadId, causing a temperary situation where conv
            // is holding onto a thread id that isn't in the database. If a new message arrives
            // and takes that thread id (because it's the next thread id to be assigned), the
            // new message will be merged with the draft message thread, causing confusion!
            if (!TextUtils.isEmpty(content)) {
                asyncUpdateDraftSmsMessage(mConversation, content);
            }
        }

        // Update state of the draft cache.
        mConversation.setDraftState(true);
    }

    public void discard() {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("discard");
        }

        // Technically, we could probably just bail out here.  But discard() is
        // really meant to be called if you never want to use the message again,
        // so keep this assert in as a debugging aid.
        if (mDiscarded == true) {
            throw new IllegalStateException("discard() called twice");
        }

        // Mark this message as discarded in order to make saveDraft() no-op.
        mDiscarded = true;

        // Delete our MMS message, if there is one.
        if (mMessageUri != null) {
            asyncDelete(mMessageUri, null, null);
        }

        // Delete any draft messages associated with this conversation.
        asyncDeleteDraftSmsMessage(mConversation);

        // Update state of the draft cache.
        mConversation.setDraftState(false);
    }

    public void unDiscard() {
        if (DEBUG) LogTag.debug("unDiscard");

        mDiscarded = false;
    }

    /**
     * Returns true if discard() has been called on this message.
     */
    public boolean isDiscarded() {
        return mDiscarded;
    }

    /**
     * To be called from our Activity's onSaveInstanceState() to give us a chance
     * to stow our state away for later retrieval.
     *
     * @param bundle The Bundle passed in to onSaveInstanceState
     */
    public void writeStateToBundle(Bundle bundle) {
        if (hasSubject()) {
            bundle.putString("subject", mSubject.toString());
        }

        if (mMessageUri != null) {
            bundle.putParcelable("msg_uri", mMessageUri);
        } else if (hasText()) {
            bundle.putString("sms_body", mText.toString());
        }
    }

    /**
     * To be called from our Activity's onCreate() if the activity manager
     * has given it a Bundle to reinflate
     * @param bundle The Bundle passed in to onCreate
     */
    public void readStateFromBundle(Bundle bundle) {
        if (bundle == null) {
            return;
        }

        String subject = bundle.getString("subject");
        setSubject(subject, false);

        Uri uri = (Uri)bundle.getParcelable("msg_uri");
        if (uri != null) {
            loadFromUri(uri);
            return;
        } else {
            String body = bundle.getString("sms_body");
            mText = body;
        }
    }

    /**
     * Update the temporary list of recipients, used when setting up a
     * new conversation.  Will be converted to a ContactList on any
     * save event (send, save draft, etc.)
     */
    public void setWorkingRecipients(List<String> numbers) {
        mWorkingRecipients = numbers;
    }

    /**
     * Set the conversation associated with this message.
     */
    public void setConversation(Conversation conv) {
        if (DEBUG) LogTag.debug("setConversation %s -> %s", mConversation, conv);

        mConversation = conv;

        // Convert to MMS if there are any email addresses in the recipient list.
        setHasEmail(conv.getRecipients().containsEmail());
    }

    /**
     * Hint whether or not this message will be delivered to an
     * an email address.
     */
    public void setHasEmail(boolean hasEmail) {
        if (MmsConfig.getEmailGateway() != null) {
            updateState(RECIPIENTS_REQUIRE_MMS, false, true);
        } else {
            updateState(RECIPIENTS_REQUIRE_MMS, hasEmail, true);
        }
    }

    /**
     * Returns true if this message would require MMS to send.
     */
    public boolean requiresMms() {
        return (mMmsState > 0);
    }

    /**
     * Set whether or not we want to send this message via MMS in order to
     * avoid sending an excessive number of concatenated SMS messages.
     */
    public void setLengthRequiresMms(boolean mmsRequired) {
        updateState(LENGTH_REQUIRES_MMS, mmsRequired, true);
    }

    private static String stateString(int state) {
        if (state == 0)
            return "<none>";

        StringBuilder sb = new StringBuilder();
        if ((state & RECIPIENTS_REQUIRE_MMS) > 0)
            sb.append("RECIPIENTS_REQUIRE_MMS | ");
        if ((state & HAS_SUBJECT) > 0)
            sb.append("HAS_SUBJECT | ");
        if ((state & HAS_ATTACHMENT) > 0)
            sb.append("HAS_ATTACHMENT | ");
        if ((state & LENGTH_REQUIRES_MMS) > 0)
            sb.append("LENGTH_REQUIRES_MMS | ");
        if ((state & FORCE_MMS) > 0)
            sb.append("FORCE_MMS | ");

        sb.delete(sb.length() - 3, sb.length());
        return sb.toString();
    }

    /**
     * Sets the current state of our various "MMS required" bits.
     *
     * @param state The bit to change, such as {@link HAS_ATTACHMENT}
     * @param on If true, set it; if false, clear it
     * @param notify Whether or not to notify the user
     */
    private void updateState(int state, boolean on, boolean notify) {
        int oldState = mMmsState;
        if (on) {
            mMmsState |= state;
        } else {
            mMmsState &= ~state;
        }

        // If we are clearing the last bit that is not FORCE_MMS,
        // expire the FORCE_MMS bit.
        if (mMmsState == FORCE_MMS && ((oldState & ~FORCE_MMS) > 0)) {
            mMmsState = 0;
        }

        // Notify the listener if we are moving from SMS to MMS
        // or vice versa.
        if (notify) {
            if (oldState == 0 && mMmsState != 0) {
                mStatusListener.onProtocolChanged(true);
            } else if (oldState != 0 && mMmsState == 0) {
                mStatusListener.onProtocolChanged(false);
            }
        }

        if (oldState != mMmsState) {
            if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) LogTag.debug("updateState: %s%s = %s",
                    on ? "+" : "-",
                    stateString(state), stateString(mMmsState));
        }
    }

    /**
     * Send this message over the network.  Will call back with onMessageSent() once
     * it has been dispatched to the telephony stack.  This WorkingMessage object is
     * no longer useful after this method has been called.
     */
    public void send() {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
            LogTag.debug("send");
        }

        // Get ready to write to disk.
        prepareForSave(true /* notify */);

        // We need the recipient list for both SMS and MMS.
        final Conversation conv = mConversation;
        String msgTxt = mText.toString();

        if (requiresMms() || addressContainsEmailToMms(conv, msgTxt)) {
            // Make local copies of the bits we need for sending a message,
            // because we will be doing it off of the main thread, which will
            // immediately continue on to resetting some of this state.
            final Uri mmsUri = mMessageUri;
            final PduPersister persister = PduPersister.getPduPersister(mContext);

            final SlideshowModel slideshow = mSlideshow;
            final SendReq sendReq = makeSendReq(conv, mSubject);

            // Make sure the text in slide 0 is no longer holding onto a reference to the text
            // in the message text box.
            slideshow.prepareForSend();

            // Do the dirty work of sending the message off of the main UI thread.
            new Thread(new Runnable() {
                public void run() {
                    sendMmsWorker(conv, mmsUri, persister, slideshow, sendReq);
                }
            }).start();
        } else {
            // Same rules apply as above.
            final String msgText = mText.toString();
            new Thread(new Runnable() {
                public void run() {
                    sendSmsWorker(conv, msgText);
                }
            }).start();
        }

        // Mark the message as discarded because it is "off the market" after being sent.
        mDiscarded = true;
    }

    private boolean addressContainsEmailToMms(Conversation conv, String text) {
        if (MmsConfig.getEmailGateway() != null) {
            String[] dests = conv.getRecipients().getNumbers();
            int length = dests.length;
            for (int i = 0; i < length; i++) {
                if (Mms.isEmailAddress(dests[i]) || MessageUtils.isAlias(dests[i])) {
                    String mtext = dests[i] + " " + text;
                    int[] params = SmsMessage.calculateLength(mtext, false);
                    if (params[0] > 1) {
                        updateState(RECIPIENTS_REQUIRE_MMS, true, true);
                        ensureSlideshow();
                        syncTextToSlideshow();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Message sending stuff

    private void sendSmsWorker(Conversation conv, String msgText) {
        mStatusListener.onPreMessageSent();
        // Make sure we are still using the correct thread ID for our
        // recipient set.
        long threadId = conv.ensureThreadId();
        String[] dests = conv.getRecipients().getNumbers();

        MessageSender sender = new SmsMessageSender(mContext, dests, msgText, threadId);
        try {
            sender.sendMessage(threadId);

            // Make sure this thread isn't over the limits in message count
            Recycler.getSmsRecycler().deleteOldMessagesByThreadId(mContext, threadId);
       } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS message, threadId=" + threadId, e);
        }

        mStatusListener.onMessageSent();
    }

    private void sendMmsWorker(Conversation conv, Uri mmsUri, PduPersister persister,
                               SlideshowModel slideshow, SendReq sendReq) {
        // First make sure we don't have too many outstanding unsent message.
        Cursor cursor = null;
        try {
            cursor = SqliteWrapper.query(mContext, mContentResolver,
                    Mms.Outbox.CONTENT_URI, MMS_OUTBOX_PROJECTION, null, null, null);
            if (cursor != null) {
                long maxMessageSize = MmsConfig.getMaxSizeScaleForPendingMmsAllowed() *
                    MmsConfig.getMaxMessageSize();
                long totalPendingSize = 0;
                while (cursor.moveToNext()) {
                    totalPendingSize += cursor.getLong(MMS_MESSAGE_SIZE_INDEX);
                }
                if (totalPendingSize >= maxMessageSize) {
                    unDiscard();    // it wasn't successfully sent. Allow it to be saved as a draft.
                    mStatusListener.onMaxPendingMessagesReached();
                    return;
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        mStatusListener.onPreMessageSent();

        // Make sure we are still using the correct thread ID for our
        // recipient set.
        long threadId = conv.ensureThreadId();

        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("sendMmsWorker: update draft MMS message " + mmsUri);
        }

        if (mmsUri == null) {
            // Create a new MMS message if one hasn't been made yet.
            mmsUri = createDraftMmsMessage(persister, sendReq, slideshow);
        } else {
            // Otherwise, sync the MMS message in progress to disk.
            updateDraftMmsMessage(mmsUri, persister, slideshow, sendReq);
        }

        // Be paranoid and clean any draft SMS up.
        deleteDraftSmsMessage(threadId);

        MessageSender sender = new MmsMessageSender(mContext, mmsUri,
                slideshow.getCurrentMessageSize());
        try {
            if (!sender.sendMessage(threadId)) {
                // The message was sent through SMS protocol, we should
                // delete the copy which was previously saved in MMS drafts.
                SqliteWrapper.delete(mContext, mContentResolver, mmsUri, null, null);
            }

            // Make sure this thread isn't over the limits in message count
            Recycler.getMmsRecycler().deleteOldMessagesByThreadId(mContext, threadId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send message: " + mmsUri + ", threadId=" + threadId, e);
        }

        mStatusListener.onMessageSent();
    }

    // Draft message stuff

    private static final String[] MMS_DRAFT_PROJECTION = {
        Mms._ID,        // 0
        Mms.SUBJECT     // 1
    };

    private static final int MMS_ID_INDEX       = 0;
    private static final int MMS_SUBJECT_INDEX  = 1;

    private static Uri readDraftMmsMessage(Context context, long threadId, StringBuilder sb) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("readDraftMmsMessage tid=%d", threadId);
        }
        Cursor cursor;
        ContentResolver cr = context.getContentResolver();

        final String selection = Mms.THREAD_ID + " = " + threadId;
        cursor = SqliteWrapper.query(context, cr,
                Mms.Draft.CONTENT_URI, MMS_DRAFT_PROJECTION,
                selection, null, null);

        Uri uri;
        try {
            if (cursor.moveToFirst()) {
                uri = ContentUris.withAppendedId(Mms.Draft.CONTENT_URI,
                        cursor.getLong(MMS_ID_INDEX));
                String subject = cursor.getString(MMS_SUBJECT_INDEX);
                if (subject != null) {
                    sb.append(subject);
                }
                return uri;
            }
        } finally {
            cursor.close();
        }

        return null;
    }

    private static SendReq makeSendReq(Conversation conv, CharSequence subject) {
        String[] dests = conv.getRecipients().getNumbers();
        SendReq req = new SendReq();
        EncodedStringValue[] encodedNumbers = EncodedStringValue.encodeStrings(dests);
        if (encodedNumbers != null) {
            req.setTo(encodedNumbers);
        }

        if (!TextUtils.isEmpty(subject)) {
            req.setSubject(new EncodedStringValue(subject.toString()));
        }

        req.setDate(System.currentTimeMillis() / 1000L);

        return req;
    }

    private static Uri createDraftMmsMessage(PduPersister persister, SendReq sendReq,
            SlideshowModel slideshow) {
        try {
            PduBody pb = slideshow.toPduBody();
            sendReq.setBody(pb);
            Uri res = persister.persist(sendReq, Mms.Draft.CONTENT_URI);
            slideshow.sync(pb);
            return res;
        } catch (MmsException e) {
            return null;
        }
    }

    private void asyncUpdateDraftMmsMessage(final Conversation conv) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("asyncUpdateDraftMmsMessage conv=%s mMessageUri=%s", conv, mMessageUri);
        }

        final PduPersister persister = PduPersister.getPduPersister(mContext);
        final SendReq sendReq = makeSendReq(conv, mSubject);

        new Thread(new Runnable() {
            public void run() {
                conv.ensureThreadId();
                conv.setDraftState(true);
                if (mMessageUri == null) {
                    mMessageUri = createDraftMmsMessage(persister, sendReq, mSlideshow);
                } else {
                    updateDraftMmsMessage(mMessageUri, persister, mSlideshow, sendReq);
                }
            }
        }).start();

        // Be paranoid and delete any SMS drafts that might be lying around.
        asyncDeleteDraftSmsMessage(conv);
    }

    private static void updateDraftMmsMessage(Uri uri, PduPersister persister,
            SlideshowModel slideshow, SendReq sendReq) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("updateDraftMmsMessage uri=%s", uri);
        }
        if (uri == null) {
            Log.e(TAG, "updateDraftMmsMessage null uri");
            return;
        }
        persister.updateHeaders(uri, sendReq);
        final PduBody pb = slideshow.toPduBody();

        try {
            persister.updateParts(uri, pb);
        } catch (MmsException e) {
            Log.e(TAG, "updateDraftMmsMessage: cannot update message " + uri);
        }

        slideshow.sync(pb);
    }

    private static final String SMS_DRAFT_WHERE = Sms.TYPE + "=" + Sms.MESSAGE_TYPE_DRAFT;
    private static final String[] SMS_BODY_PROJECTION = { Sms.BODY };
    private static final int SMS_BODY_INDEX = 0;

    /**
     * Reads a draft message for the given thread ID from the database,
     * if there is one, deletes it from the database, and returns it.
     * @return The draft message or an empty string.
     */
    private static String readDraftSmsMessage(Context context, long thread_id, Conversation conv) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("readDraftSmsMessage tid=%d", thread_id);
        }
        ContentResolver cr = context.getContentResolver();

        // If it's an invalid thread, don't bother.
        if (thread_id <= 0) {
            return "";
        }

        Uri thread_uri = ContentUris.withAppendedId(Sms.Conversations.CONTENT_URI, thread_id);
        String body = "";

        Cursor c = SqliteWrapper.query(context, cr,
                        thread_uri, SMS_BODY_PROJECTION, SMS_DRAFT_WHERE, null, null);
        try {
            if (c.moveToFirst()) {
                body = c.getString(SMS_BODY_INDEX);
            }
        } finally {
            c.close();
        }

        // Clean out drafts for this thread -- if the recipient set changes,
        // we will lose track of the original draft and be unable to delete
        // it later.  The message will be re-saved if necessary upon exit of
        // the activity.
        SqliteWrapper.delete(context, cr, thread_uri, SMS_DRAFT_WHERE, null);

        // We found a draft, and if there are no messages in the conversation,
        // that means we deleted the thread, too. Must reset the thread id
        // so we'll eventually create a new thread.
        if (conv.getMessageCount() == 0) {
            if (DEBUG) LogTag.debug("readDraftSmsMessage calling clearThreadId");
            conv.clearThreadId();
        }

        return body;
    }

    private void asyncUpdateDraftSmsMessage(final Conversation conv, final String contents) {
        new Thread(new Runnable() {
            public void run() {
                long threadId = conv.ensureThreadId();
                conv.setDraftState(true);
                updateDraftSmsMessage(threadId, contents);
            }
        }).start();
    }

    private void updateDraftSmsMessage(long thread_id, String contents) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("updateDraftSmsMessage tid=%d, contents=\"%s\"", thread_id, contents);
        }

        // If we don't have a valid thread, there's nothing to do.
        if (thread_id <= 0) {
            return;
        }

        ContentValues values = new ContentValues(3);
        values.put(Sms.THREAD_ID, thread_id);
        values.put(Sms.BODY, contents);
        values.put(Sms.TYPE, Sms.MESSAGE_TYPE_DRAFT);
        SqliteWrapper.insert(mContext, mContentResolver, Sms.CONTENT_URI, values);
        asyncDeleteDraftMmsMessage(thread_id);
    }

    private void asyncDelete(final Uri uri, final String selection, final String[] selectionArgs) {
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            LogTag.debug("asyncDelete %s where %s", uri, selection);
        }
        new Thread(new Runnable() {
            public void run() {
                SqliteWrapper.delete(mContext, mContentResolver, uri, selection, selectionArgs);
            }
        }).start();
    }

    private void asyncDeleteDraftSmsMessage(Conversation conv) {
        long threadId = conv.getThreadId();
        if (threadId > 0) {
            asyncDelete(ContentUris.withAppendedId(Sms.Conversations.CONTENT_URI, threadId),
                SMS_DRAFT_WHERE, null);
        }
    }

    private void deleteDraftSmsMessage(long threadId) {
        SqliteWrapper.delete(mContext, mContentResolver,
                ContentUris.withAppendedId(Sms.Conversations.CONTENT_URI, threadId),
                SMS_DRAFT_WHERE, null);
    }

    private void asyncDeleteDraftMmsMessage(long threadId) {
        final String where = Mms.THREAD_ID + " = " + threadId;
        asyncDelete(Mms.Draft.CONTENT_URI, where, null);
    }

}
