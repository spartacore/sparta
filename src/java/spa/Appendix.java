/*
 * Copyright © 2013-2016 The Nxt Core Developers.
 * Copyright © 2016-2017 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */

package spa;

import spa.AccountLedger.LedgerEvent;
import spa.crypto.Crypto;
import spa.crypto.EncryptedData;
import spa.util.Convert;
import spa.util.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public interface Appendix {

    int getSize();
    int getFullSize();
    void putBytes(ByteBuffer buffer);
    JSONObject getJSONObject();
    byte getVersion();
    int getBaselineFeeHeight();
    Fee getBaselineFee(Transaction transaction);
    int getNextFeeHeight();
    Fee getNextFee(Transaction transaction);
    boolean isPhased(Transaction transaction);

    interface Prunable {
        byte[] getHash();
        boolean hasPrunableData();
        void restorePrunableData(Transaction transaction, int blockTimestamp, int height);
        default boolean shouldLoadPrunable(Transaction transaction, boolean includeExpiredPrunable) {
            return Spa.getEpochTime() - transaction.getTimestamp() <
                    (includeExpiredPrunable && Constants.INCLUDE_EXPIRED_PRUNABLE ?
                            Constants.MAX_PRUNABLE_LIFETIME : Constants.MIN_PRUNABLE_LIFETIME);
        }
    }

    interface Encryptable {
        void encrypt(String secretPhrase);
    }


    abstract class AbstractAppendix implements Appendix {

        private final byte version;

        AbstractAppendix(JSONObject attachmentData) {
            Long l = (Long) attachmentData.get("version." + getAppendixName());
            version = (byte) (l == null ? 0 : l);
        }

        AbstractAppendix(ByteBuffer buffer, byte transactionVersion) {
            if (transactionVersion == 0) {
                version = 0;
            } else {
                version = buffer.get();
            }
        }

        AbstractAppendix(int version) {
            this.version = (byte) version;
        }

        AbstractAppendix() {
            this.version = 1;
        }

        abstract String getAppendixName();

        @Override
        public final int getSize() {
            return getMySize() + (version > 0 ? 1 : 0);
        }

        @Override
        public final int getFullSize() {
            return getMyFullSize() + (version > 0 ? 1 : 0);
        }

        abstract int getMySize();

        int getMyFullSize() {
            return getMySize();
        }

        @Override
        public final void putBytes(ByteBuffer buffer) {
            if (version > 0) {
                buffer.put(version);
            }
            putMyBytes(buffer);
        }

        abstract void putMyBytes(ByteBuffer buffer);

        @Override
        public final JSONObject getJSONObject() {
            JSONObject json = new JSONObject();
            json.put("version." + getAppendixName(), version);
            putMyJSON(json);
            return json;
        }

        abstract void putMyJSON(JSONObject json);

        @Override
        public final byte getVersion() {
            return version;
        }

        boolean verifyVersion(byte transactionVersion) {
            return transactionVersion == 0 ? version == 0 : version == 1;
        }

        @Override
        public int getBaselineFeeHeight() {
            return Constants.SHUFFLING_BLOCK;
        }

        @Override
        public Fee getBaselineFee(Transaction transaction) {
            return Fee.NONE;
        }

        @Override
        public int getNextFeeHeight() {
            return Integer.MAX_VALUE;
        }

        @Override
        public Fee getNextFee(Transaction transaction) {
            return getBaselineFee(transaction);
        }

        abstract void validate(Transaction transaction) throws SpaException.ValidationException;

        void validateAtFinish(Transaction transaction) throws SpaException.ValidationException {
            if (!isPhased(transaction)) {
                return;
            }
            validate(transaction);
        }

        abstract void apply(Transaction transaction, Account senderAccount, Account recipientAccount);

        final void loadPrunable(Transaction transaction) {
            loadPrunable(transaction, false);
        }

        void loadPrunable(Transaction transaction, boolean includeExpiredPrunable) {}

        abstract boolean isPhasable();

        @Override
        public final boolean isPhased(Transaction transaction) {
            return false;
        }

    }

    static boolean hasAppendix(String appendixName, JSONObject attachmentData) {
        return attachmentData.get("version." + appendixName) != null;
    }

    class Message extends AbstractAppendix {

        private static final String appendixName = "Message";

        static Message parse(JSONObject attachmentData) {
            if (!hasAppendix(appendixName, attachmentData)) {
                return null;
            }
            return new Message(attachmentData);
        }

        private static final Fee MESSAGE_FEE = new Fee.SizeBasedFee(0, Constants.ONE_SPA, 32) {
            @Override
            public int getSize(TransactionImpl transaction, Appendix appendage) {
                return ((Message)appendage).getMessage().length;
            }
        };

        private final byte[] message;
        private final boolean isText;

        Message(ByteBuffer buffer, byte transactionVersion) throws SpaException.NotValidException {
            super(buffer, transactionVersion);
            int messageLength = buffer.getInt();
            this.isText = messageLength < 0; // ugly hack
            if (messageLength < 0) {
                messageLength &= Integer.MAX_VALUE;
            }
            if (messageLength > 1000) {
                throw new SpaException.NotValidException("Invalid arbitrary message length: " + messageLength);
            }
            this.message = new byte[messageLength];
            buffer.get(this.message);
            if (isText && !Arrays.equals(message, Convert.toBytes(Convert.toString(message)))) {
                throw new SpaException.NotValidException("Message is not UTF-8 text");
            }
        }

        Message(JSONObject attachmentData) {
            super(attachmentData);
            String messageString = (String)attachmentData.get("message");
            this.isText = Boolean.TRUE.equals(attachmentData.get("messageIsText"));
            this.message = isText ? Convert.toBytes(messageString) : Convert.parseHexString(messageString);
        }

        public Message(byte[] message) {
            this(message, false);
        }

        public Message(String string) {
            this(Convert.toBytes(string), true);
        }

        public Message(String string, boolean isText) {
            this(isText ? Convert.toBytes(string) : Convert.parseHexString(string), isText);
        }

        public Message(byte[] message, boolean isText) {
            this.message = message;
            this.isText = isText;
        }

        @Override
        String getAppendixName() {
            return appendixName;
        }

        @Override
        int getMySize() {
            return 4 + message.length;
        }

        @Override
        void putMyBytes(ByteBuffer buffer) {
            buffer.putInt(isText ? (message.length | Integer.MIN_VALUE) : message.length);
            buffer.put(message);
        }

        @Override
        void putMyJSON(JSONObject json) {
            json.put("message", Convert.toString(message, isText));
            json.put("messageIsText", isText);
        }

        @Override
        public Fee getBaselineFee(Transaction transaction) {
            return MESSAGE_FEE;
        }

        @Override
        void validate(Transaction transaction) throws SpaException.ValidationException {
        }

        @Override
        void apply(Transaction transaction, Account senderAccount, Account recipientAccount) {}

        public byte[] getMessage() {
            return message;
        }

        public boolean isText() {
            return isText;
        }

        @Override
        boolean isPhasable() {
            return false;
        }

    }
//
//    abstract class AbstractEncryptedMessage extends AbstractAppendix {
//
//        private static final Fee ENCRYPTED_MESSAGE_FEE = new Fee.SizeBasedFee(Constants.ONE_SPA, Constants.ONE_SPA, 32) {
//            @Override
//            public int getSize(TransactionImpl transaction, Appendix appendage) {
//                return ((AbstractEncryptedMessage)appendage).getEncryptedDataLength() - 16;
//            }
//        };
//
//        private EncryptedData encryptedData;
//        private final boolean isText;
//        private final boolean isCompressed;
//
//        private AbstractEncryptedMessage(ByteBuffer buffer, byte transactionVersion) throws SpaException.NotValidException {
//            super(buffer, transactionVersion);
//            int length = buffer.getInt();
//            this.isText = length < 0;
//            if (length < 0) {
//                length &= Integer.MAX_VALUE;
//            }
//            this.encryptedData = EncryptedData.readEncryptedData(buffer, length, 1000);
//            this.isCompressed = getVersion() != 2;
//        }
//
//        private AbstractEncryptedMessage(JSONObject attachmentJSON, JSONObject encryptedMessageJSON) {
//            super(attachmentJSON);
//            byte[] data = Convert.parseHexString((String)encryptedMessageJSON.get("data"));
//            byte[] nonce = Convert.parseHexString((String) encryptedMessageJSON.get("nonce"));
//            this.encryptedData = new EncryptedData(data, nonce);
//            this.isText = Boolean.TRUE.equals(encryptedMessageJSON.get("isText"));
//            Object isCompressed = encryptedMessageJSON.get("isCompressed");
//            this.isCompressed = isCompressed == null || Boolean.TRUE.equals(isCompressed);
//        }
//
//        private AbstractEncryptedMessage(EncryptedData encryptedData, boolean isText, boolean isCompressed) {
//            super(isCompressed ? 1 : 2);
//            this.encryptedData = encryptedData;
//            this.isText = isText;
//            this.isCompressed = isCompressed;
//        }
//
//        @Override
//        int getMySize() {
//            return 4 + encryptedData.getSize();
//        }
//
//        @Override
//        void putMyBytes(ByteBuffer buffer) {
//            buffer.putInt(isText ? (encryptedData.getData().length | Integer.MIN_VALUE) : encryptedData.getData().length);
//            buffer.put(encryptedData.getData());
//            buffer.put(encryptedData.getNonce());
//        }
//
//        @Override
//        void putMyJSON(JSONObject json) {
//            json.put("data", Convert.toHexString(encryptedData.getData()));
//            json.put("nonce", Convert.toHexString(encryptedData.getNonce()));
//            json.put("isText", isText);
//            json.put("isCompressed", isCompressed);
//        }
//
//        @Override
//        public Fee getBaselineFee(Transaction transaction) {
//            return ENCRYPTED_MESSAGE_FEE;
//        }
//
//        @Override
//        void validate(Transaction transaction) throws SpaException.ValidationException {
//            if (Spa.getBlockchain().getHeight() > Constants.SHUFFLING_BLOCK && getEncryptedDataLength() > Constants.MAX_ENCRYPTED_MESSAGE_LENGTH) {
//                throw new SpaException.NotValidException("Max encrypted message length exceeded");
//            }
//            if (encryptedData != null) {
//                if ((encryptedData.getNonce().length != 32 && encryptedData.getData().length > 0)
//                        || (encryptedData.getNonce().length != 0 && encryptedData.getData().length == 0)) {
//                    throw new SpaException.NotValidException("Invalid nonce length " + encryptedData.getNonce().length);
//                }
//            }
//            if ((getVersion() != 2 && !isCompressed) || (getVersion() == 2 && isCompressed)) {
//                throw new SpaException.NotValidException("Version mismatch - version " + getVersion() + ", isCompressed " + isCompressed);
//            }
//        }
//
//        @Override
//        final boolean verifyVersion(byte transactionVersion) {
//            return transactionVersion == 0 ? getVersion() == 0 : (getVersion() == 1 || getVersion() == 2);
//        }
//
//        @Override
//        void apply(Transaction transaction, Account senderAccount, Account recipientAccount) {}
//
//        public final EncryptedData getEncryptedData() {
//            return encryptedData;
//        }
//
//        final void setEncryptedData(EncryptedData encryptedData) {
//            this.encryptedData = encryptedData;
//        }
//
//        int getEncryptedDataLength() {
//            return encryptedData.getData().length;
//        }
//
//        public final boolean isText() {
//            return isText;
//        }
//
//        public final boolean isCompressed() {
//            return isCompressed;
//        }
//
//        @Override
//        final boolean isPhasable() {
//            return false;
//        }
//
//    }
//
//    class PrunableEncryptedMessage extends AbstractAppendix implements Prunable {
//
//        private static final String appendixName = "PrunableEncryptedMessage";
//
//        private static final Fee PRUNABLE_ENCRYPTED_DATA_FEE = new Fee.SizeBasedFee(Constants.ONE_SPA /10) {
//            @Override
//            public int getSize(TransactionImpl transaction, Appendix appendix) {
//                return appendix.getFullSize();
//            }
//        };
//
//        static PrunableEncryptedMessage parse(JSONObject attachmentData) {
//            if (!hasAppendix(appendixName, attachmentData)) {
//                return null;
//            }
//            JSONObject encryptedMessageJSON = (JSONObject)attachmentData.get("encryptedMessage");
//            if (encryptedMessageJSON != null && encryptedMessageJSON.get("data") == null) {
//                return new UnencryptedPrunableEncryptedMessage(attachmentData);
//            }
//            return new PrunableEncryptedMessage(attachmentData);
//        }
//
//        private final byte[] hash;
//        private EncryptedData encryptedData;
//        private final boolean isText;
//        private final boolean isCompressed;
//        private volatile PrunableMessage prunableMessage;
//
//        PrunableEncryptedMessage(ByteBuffer buffer, byte transactionVersion) {
//            super(buffer, transactionVersion);
//            this.hash = new byte[32];
//            buffer.get(this.hash);
//            this.encryptedData = null;
//            this.isText = false;
//            this.isCompressed = false;
//        }
//
//        private PrunableEncryptedMessage(JSONObject attachmentJSON) {
//            super(attachmentJSON);
//            String hashString = Convert.emptyToNull((String) attachmentJSON.get("encryptedMessageHash"));
//            JSONObject encryptedMessageJSON = (JSONObject) attachmentJSON.get("encryptedMessage");
//            if (hashString != null && encryptedMessageJSON == null) {
//                this.hash = Convert.parseHexString(hashString);
//                this.encryptedData = null;
//                this.isText = false;
//                this.isCompressed = false;
//            } else {
//                this.hash = null;
//                byte[] data = Convert.parseHexString((String) encryptedMessageJSON.get("data"));
//                byte[] nonce = Convert.parseHexString((String) encryptedMessageJSON.get("nonce"));
//                this.encryptedData = new EncryptedData(data, nonce);
//                this.isText = Boolean.TRUE.equals(encryptedMessageJSON.get("isText"));
//                this.isCompressed = Boolean.TRUE.equals(encryptedMessageJSON.get("isCompressed"));
//            }
//        }
//
//        public PrunableEncryptedMessage(EncryptedData encryptedData, boolean isText, boolean isCompressed) {
//            this.encryptedData = encryptedData;
//            this.isText = isText;
//            this.isCompressed = isCompressed;
//            this.hash = null;
//        }
//
//        @Override
//        public final Fee getBaselineFee(Transaction transaction) {
//            return PRUNABLE_ENCRYPTED_DATA_FEE;
//        }
//
//        @Override
//        final int getMySize() {
//            return 32;
//        }
//
//        @Override
//        final int getMyFullSize() {
//            return getEncryptedDataLength();
//        }
//
//        @Override
//        void putMyBytes(ByteBuffer buffer) {
//            buffer.put(getHash());
//        }
//
//        @Override
//        void putMyJSON(JSONObject json) {
//            if (prunableMessage != null) {
//                JSONObject encryptedMessageJSON = new JSONObject();
//                json.put("encryptedMessage", encryptedMessageJSON);
//                encryptedMessageJSON.put("data", Convert.toHexString(prunableMessage.getEncryptedData().getData()));
//                encryptedMessageJSON.put("nonce", Convert.toHexString(prunableMessage.getEncryptedData().getNonce()));
//                encryptedMessageJSON.put("isText", prunableMessage.encryptedMessageIsText());
//                encryptedMessageJSON.put("isCompressed", prunableMessage.isCompressed());
//            } else if (encryptedData != null) {
//                JSONObject encryptedMessageJSON = new JSONObject();
//                json.put("encryptedMessage", encryptedMessageJSON);
//                encryptedMessageJSON.put("data", Convert.toHexString(encryptedData.getData()));
//                encryptedMessageJSON.put("nonce", Convert.toHexString(encryptedData.getNonce()));
//                encryptedMessageJSON.put("isText", isText);
//                encryptedMessageJSON.put("isCompressed", isCompressed);
//            }
//            json.put("encryptedMessageHash", Convert.toHexString(getHash()));
//        }
//
//        @Override
//        final String getAppendixName() {
//            return appendixName;
//        }
//
//        @Override
//        void validate(Transaction transaction) throws SpaException.ValidationException {
//            if (transaction.getEncryptedMessage() != null) {
//                throw new SpaException.NotValidException("Cannot have both encrypted and prunable encrypted message attachments");
//            }
//            EncryptedData ed = getEncryptedData();
//            if (ed == null && Spa.getEpochTime() - transaction.getTimestamp() < Constants.MIN_PRUNABLE_LIFETIME) {
//                throw new SpaException.NotCurrentlyValidException("Encrypted message has been pruned prematurely");
//            }
//            if (ed != null) {
//                if (ed.getData().length > Constants.MAX_PRUNABLE_ENCRYPTED_MESSAGE_LENGTH) {
//                    throw new SpaException.NotValidException(String.format("Message length %d exceeds max prunable encrypted message length %d",
//                            ed.getData().length, Constants.MAX_PRUNABLE_ENCRYPTED_MESSAGE_LENGTH));
//                }
//                if ((ed.getNonce().length != 32 && ed.getData().length > 0)
//                        || (ed.getNonce().length != 0 && ed.getData().length == 0)) {
//                    throw new SpaException.NotValidException("Invalid nonce length " + ed.getNonce().length);
//                }
//            }
//            if (transaction.getRecipientId() == 0) {
//                throw new SpaException.NotValidException("Encrypted messages cannot be attached to transactions with no recipient");
//            }
//        }
//
//        @Override
//        void apply(Transaction transaction, Account senderAccount, Account recipientAccount) {
//            if (Spa.getEpochTime() - transaction.getTimestamp() < Constants.MAX_PRUNABLE_LIFETIME) {
//                PrunableMessage.add((TransactionImpl)transaction, this);
//            }
//        }
//
//        public final EncryptedData getEncryptedData() {
//            if (prunableMessage != null) {
//                return prunableMessage.getEncryptedData();
//            }
//            return encryptedData;
//        }
//
//        final void setEncryptedData(EncryptedData encryptedData) {
//            this.encryptedData = encryptedData;
//        }
//
//        int getEncryptedDataLength() {
//            return getEncryptedData() == null ? 0 : getEncryptedData().getData().length;
//        }
//
//        public final boolean isText() {
//            if (prunableMessage != null) {
//                return prunableMessage.encryptedMessageIsText();
//            }
//            return isText;
//        }
//
//        public final boolean isCompressed() {
//            if (prunableMessage != null) {
//                return prunableMessage.isCompressed();
//            }
//            return isCompressed;
//        }
//
//        @Override
//        public final byte[] getHash() {
//            if (hash != null) {
//                return hash;
//            }
//            MessageDigest digest = Crypto.sha256();
//            digest.update((byte)(isText ? 1 : 0));
//            digest.update((byte)(isCompressed ? 1 : 0));
//            digest.update(encryptedData.getData());
//            digest.update(encryptedData.getNonce());
//            return digest.digest();
//        }
//
//        @Override
//        void loadPrunable(Transaction transaction, boolean includeExpiredPrunable) {
//            if (!hasPrunableData() && shouldLoadPrunable(transaction, includeExpiredPrunable)) {
//                PrunableMessage prunableMessage = PrunableMessage.getPrunableMessage(transaction.getId());
//                if (prunableMessage != null && prunableMessage.getEncryptedData() != null) {
//                    this.prunableMessage = prunableMessage;
//                }
//            }
//        }
//
//        @Override
//        final boolean isPhasable() {
//            return false;
//        }
//
//        @Override
//        public final boolean hasPrunableData() {
//            return (prunableMessage != null || encryptedData != null);
//        }
//
//        @Override
//        public void restorePrunableData(Transaction transaction, int blockTimestamp, int height) {
//            PrunableMessage.add((TransactionImpl)transaction, this, blockTimestamp, height);
//        }
//    }
//
//    final class UnencryptedPrunableEncryptedMessage extends PrunableEncryptedMessage implements Encryptable {
//
//        private final byte[] messageToEncrypt;
//        private final byte[] recipientPublicKey;
//
//        private UnencryptedPrunableEncryptedMessage(JSONObject attachmentJSON) {
//            super(attachmentJSON);
//            setEncryptedData(null);
//            JSONObject encryptedMessageJSON = (JSONObject)attachmentJSON.get("encryptedMessage");
//            String messageToEncryptString = (String)encryptedMessageJSON.get("messageToEncrypt");
//            this.messageToEncrypt = isText() ? Convert.toBytes(messageToEncryptString) : Convert.parseHexString(messageToEncryptString);
//            this.recipientPublicKey = Convert.parseHexString((String)attachmentJSON.get("recipientPublicKey"));
//        }
//
//        public UnencryptedPrunableEncryptedMessage(byte[] messageToEncrypt, boolean isText, boolean isCompressed, byte[] recipientPublicKey) {
//            super(null, isText, isCompressed);
//            this.messageToEncrypt = messageToEncrypt;
//            this.recipientPublicKey = recipientPublicKey;
//        }
//
//        @Override
//        void putMyBytes(ByteBuffer buffer) {
//            if (getEncryptedData() == null) {
//                throw new SpaException.NotYetEncryptedException("Prunable encrypted message not yet encrypted");
//            }
//            super.putMyBytes(buffer);
//        }
//
//        @Override
//        void putMyJSON(JSONObject json) {
//            if (getEncryptedData() == null) {
//                JSONObject encryptedMessageJSON = new JSONObject();
//                encryptedMessageJSON.put("messageToEncrypt", isText() ? Convert.toString(messageToEncrypt) : Convert.toHexString(messageToEncrypt));
//                encryptedMessageJSON.put("isText", isText());
//                encryptedMessageJSON.put("isCompressed", isCompressed());
//                json.put("recipientPublicKey", Convert.toHexString(recipientPublicKey));
//                json.put("encryptedMessage", encryptedMessageJSON);
//            } else {
//                super.putMyJSON(json);
//            }
//        }
//
//        @Override
//        void validate(Transaction transaction) throws SpaException.ValidationException {
//            if (getEncryptedData() == null) {
//                int dataLength = getEncryptedDataLength();
//                if (dataLength > Constants.MAX_PRUNABLE_ENCRYPTED_MESSAGE_LENGTH) {
//                    throw new SpaException.NotValidException(String.format("Message length %d exceeds max prunable encrypted message length %d",
//                            dataLength, Constants.MAX_PRUNABLE_ENCRYPTED_MESSAGE_LENGTH));
//                }
//            } else {
//                super.validate(transaction);
//            }
//        }
//
//        @Override
//        void apply(Transaction transaction, Account senderAccount, Account recipientAccount) {
//            if (getEncryptedData() == null) {
//                throw new SpaException.NotYetEncryptedException("Prunable encrypted message not yet encrypted");
//            }
//            super.apply(transaction, senderAccount, recipientAccount);
//        }
//
//        @Override
//        void loadPrunable(Transaction transaction, boolean includeExpiredPrunable) {}
//
//        @Override
//        public void encrypt(String secretPhrase) {
//            setEncryptedData(EncryptedData.encrypt(getPlaintext(), secretPhrase, recipientPublicKey));
//        }
//
//        @Override
//        int getEncryptedDataLength() {
//            return EncryptedData.getEncryptedDataLength(getPlaintext());
//        }
//
//        private byte[] getPlaintext() {
//            return isCompressed() && messageToEncrypt.length > 0 ? Convert.compress(messageToEncrypt) : messageToEncrypt;
//        }
//
//    }
//
//    class EncryptedMessage extends AbstractEncryptedMessage {
//
//        private static final String appendixName = "EncryptedMessage";
//
//        static EncryptedMessage parse(JSONObject attachmentData) {
//            if (!hasAppendix(appendixName, attachmentData)) {
//                return null;
//            }
//            if (((JSONObject)attachmentData.get("encryptedMessage")).get("data") == null) {
//                return new UnencryptedEncryptedMessage(attachmentData);
//            }
//            return new EncryptedMessage(attachmentData);
//        }
//
//        EncryptedMessage(ByteBuffer buffer, byte transactionVersion) throws SpaException.NotValidException {
//            super(buffer, transactionVersion);
//        }
//
//        EncryptedMessage(JSONObject attachmentData) {
//            super(attachmentData, (JSONObject)attachmentData.get("encryptedMessage"));
//        }
//
//        public EncryptedMessage(EncryptedData encryptedData, boolean isText, boolean isCompressed) {
//            super(encryptedData, isText, isCompressed);
//        }
//
//        @Override
//        final String getAppendixName() {
//            return appendixName;
//        }
//
//        @Override
//        void putMyJSON(JSONObject json) {
//            JSONObject encryptedMessageJSON = new JSONObject();
//            super.putMyJSON(encryptedMessageJSON);
//            json.put("encryptedMessage", encryptedMessageJSON);
//        }
//
//        @Override
//        void validate(Transaction transaction) throws SpaException.ValidationException {
//            super.validate(transaction);
//            if (transaction.getRecipientId() == 0) {
//                throw new SpaException.NotValidException("Encrypted messages cannot be attached to transactions with no recipient");
//            }
//        }
//
//    }
//
//    final class UnencryptedEncryptedMessage extends EncryptedMessage implements Encryptable {
//
//        private final byte[] messageToEncrypt;
//        private final byte[] recipientPublicKey;
//
//        UnencryptedEncryptedMessage(JSONObject attachmentData) {
//            super(attachmentData);
//            setEncryptedData(null);
//            JSONObject encryptedMessageJSON = (JSONObject)attachmentData.get("encryptedMessage");
//            String messageToEncryptString = (String)encryptedMessageJSON.get("messageToEncrypt");
//            messageToEncrypt = isText() ? Convert.toBytes(messageToEncryptString) : Convert.parseHexString(messageToEncryptString);
//            recipientPublicKey = Convert.parseHexString((String)attachmentData.get("recipientPublicKey"));
//        }
//
//        public UnencryptedEncryptedMessage(byte[] messageToEncrypt, boolean isText, boolean isCompressed, byte[] recipientPublicKey) {
//            super(null, isText, isCompressed);
//            this.messageToEncrypt = messageToEncrypt;
//            this.recipientPublicKey = recipientPublicKey;
//        }
//
//        @Override
//        int getMySize() {
//            if (getEncryptedData() != null) {
//                return super.getMySize();
//            }
//            return 4 + EncryptedData.getEncryptedSize(getPlaintext());
//        }
//
//        @Override
//        void putMyBytes(ByteBuffer buffer) {
//            if (getEncryptedData() == null) {
//                throw new SpaException.NotYetEncryptedException("Message not yet encrypted");
//            }
//            super.putMyBytes(buffer);
//        }
//
//        @Override
//        void putMyJSON(JSONObject json) {
//            if (getEncryptedData() == null) {
//                JSONObject encryptedMessageJSON = new JSONObject();
//                encryptedMessageJSON.put("messageToEncrypt", isText() ? Convert.toString(messageToEncrypt) : Convert.toHexString(messageToEncrypt));
//                encryptedMessageJSON.put("isText", isText());
//                encryptedMessageJSON.put("isCompressed", isCompressed());
//                json.put("encryptedMessage", encryptedMessageJSON);
//                json.put("recipientPublicKey", Convert.toHexString(recipientPublicKey));
//            } else {
//                super.putMyJSON(json);
//            }
//        }
//
//        @Override
//        void apply(Transaction transaction, Account senderAccount, Account recipientAccount) {
//            if (getEncryptedData() == null) {
//                throw new SpaException.NotYetEncryptedException("Message not yet encrypted");
//            }
//            super.apply(transaction, senderAccount, recipientAccount);
//        }
//
//        @Override
//        public void encrypt(String secretPhrase) {
//            setEncryptedData(EncryptedData.encrypt(getPlaintext(), secretPhrase, recipientPublicKey));
//        }
//
//        private byte[] getPlaintext() {
//            return isCompressed() && messageToEncrypt.length > 0 ? Convert.compress(messageToEncrypt) : messageToEncrypt;
//        }
//
//        @Override
//        int getEncryptedDataLength() {
//            return EncryptedData.getEncryptedDataLength(getPlaintext());
//        }
//
//    }

//    class EncryptToSelfMessage extends AbstractEncryptedMessage {
//
//        private static final String appendixName = "EncryptToSelfMessage";
//
//        static EncryptToSelfMessage parse(JSONObject attachmentData) {
//            if (!hasAppendix(appendixName, attachmentData)) {
//                return null;
//            }
//            if (((JSONObject)attachmentData.get("encryptToSelfMessage")).get("data") == null) {
//                return new UnencryptedEncryptToSelfMessage(attachmentData);
//            }
//            return new EncryptToSelfMessage(attachmentData);
//        }
//
//        EncryptToSelfMessage(ByteBuffer buffer, byte transactionVersion) throws SpaException.NotValidException {
//            super(buffer, transactionVersion);
//        }
//
//        EncryptToSelfMessage(JSONObject attachmentData) {
//            super(attachmentData, (JSONObject)attachmentData.get("encryptToSelfMessage"));
//        }
//
//        public EncryptToSelfMessage(EncryptedData encryptedData, boolean isText, boolean isCompressed) {
//            super(encryptedData, isText, isCompressed);
//        }
//
//        @Override
//        final String getAppendixName() {
//            return appendixName;
//        }
//
//        @Override
//        void putMyJSON(JSONObject json) {
//            JSONObject encryptToSelfMessageJSON = new JSONObject();
//            super.putMyJSON(encryptToSelfMessageJSON);
//            json.put("encryptToSelfMessage", encryptToSelfMessageJSON);
//        }
//
//    }

//    final class UnencryptedEncryptToSelfMessage extends EncryptToSelfMessage implements Encryptable {
//
//        private final byte[] messageToEncrypt;
//
//        UnencryptedEncryptToSelfMessage(JSONObject attachmentData) {
//            super(attachmentData);
//            setEncryptedData(null);
//            JSONObject encryptedMessageJSON = (JSONObject)attachmentData.get("encryptToSelfMessage");
//            String messageToEncryptString = (String)encryptedMessageJSON.get("messageToEncrypt");
//            messageToEncrypt = isText() ? Convert.toBytes(messageToEncryptString) : Convert.parseHexString(messageToEncryptString);
//        }
//
//        public UnencryptedEncryptToSelfMessage(byte[] messageToEncrypt, boolean isText, boolean isCompressed) {
//            super(null, isText, isCompressed);
//            this.messageToEncrypt = messageToEncrypt;
//        }
//
//        @Override
//        int getMySize() {
//            if (getEncryptedData() != null) {
//                return super.getMySize();
//            }
//            return 4 + EncryptedData.getEncryptedSize(getPlaintext());
//        }
//
//        @Override
//        void putMyBytes(ByteBuffer buffer) {
//            if (getEncryptedData() == null) {
//                throw new SpaException.NotYetEncryptedException("Message not yet encrypted");
//            }
//            super.putMyBytes(buffer);
//        }
//
//        @Override
//        void putMyJSON(JSONObject json) {
//            if (getEncryptedData() == null) {
//                JSONObject encryptedMessageJSON = new JSONObject();
//                encryptedMessageJSON.put("messageToEncrypt", isText() ? Convert.toString(messageToEncrypt) : Convert.toHexString(messageToEncrypt));
//                encryptedMessageJSON.put("isText", isText());
//                encryptedMessageJSON.put("isCompressed", isCompressed());
//                json.put("encryptToSelfMessage", encryptedMessageJSON);
//            } else {
//                super.putMyJSON(json);
//            }
//        }
//
//        @Override
//        void apply(Transaction transaction, Account senderAccount, Account recipientAccount) {
//            if (getEncryptedData() == null) {
//                throw new SpaException.NotYetEncryptedException("Message not yet encrypted");
//            }
//            super.apply(transaction, senderAccount, recipientAccount);
//        }
//
//        @Override
//        public void encrypt(String secretPhrase) {
//            setEncryptedData(EncryptedData.encrypt(getPlaintext(), secretPhrase, Crypto.getPublicKey(secretPhrase)));
//        }
//
//        @Override
//        int getEncryptedDataLength() {
//            return EncryptedData.getEncryptedDataLength(getPlaintext());
//        }
//
//        private byte[] getPlaintext() {
//            return isCompressed() && messageToEncrypt.length > 0 ? Convert.compress(messageToEncrypt) : messageToEncrypt;
//        }
//
//    }

    final class PublicKeyAnnouncement extends AbstractAppendix {

        private static final String appendixName = "PublicKeyAnnouncement";

        static PublicKeyAnnouncement parse(JSONObject attachmentData) {
            if (!hasAppendix(appendixName, attachmentData)) {
                return null;
            }
            return new PublicKeyAnnouncement(attachmentData);
        }

        private final byte[] publicKey;

        PublicKeyAnnouncement(ByteBuffer buffer, byte transactionVersion) {
            super(buffer, transactionVersion);
            this.publicKey = new byte[32];
            buffer.get(this.publicKey);
        }

        PublicKeyAnnouncement(JSONObject attachmentData) {
            super(attachmentData);
            this.publicKey = Convert.parseHexString((String)attachmentData.get("recipientPublicKey"));
        }

        public PublicKeyAnnouncement(byte[] publicKey) {
            this.publicKey = publicKey;
        }

        @Override
        String getAppendixName() {
            return appendixName;
        }

        @Override
        int getMySize() {
            return 32;
        }

        @Override
        void putMyBytes(ByteBuffer buffer) {
            buffer.put(publicKey);
        }

        @Override
        void putMyJSON(JSONObject json) {
            json.put("recipientPublicKey", Convert.toHexString(publicKey));
        }

        @Override
        void validate(Transaction transaction) throws SpaException.ValidationException {
            if (transaction.getRecipientId() == 0) {
                throw new SpaException.NotValidException("PublicKeyAnnouncement cannot be attached to transactions with no recipient");
            }
            if (!Crypto.isCanonicalPublicKey(publicKey)) {
                throw new SpaException.NotValidException("Invalid recipient public key: " + Convert.toHexString(publicKey));
            }
            long recipientId = transaction.getRecipientId();
            if (Account.getId(this.publicKey) != recipientId) {
                throw new SpaException.NotValidException("Announced public key does not match recipient accountId");
            }
            byte[] recipientPublicKey = Account.getPublicKey(recipientId);
            if (recipientPublicKey != null && ! Arrays.equals(publicKey, recipientPublicKey)) {
                throw new SpaException.NotCurrentlyValidException("A different public key for this account has already been announced");
            }
        }

        @Override
        void apply(Transaction transaction, Account senderAccount, Account recipientAccount) {
            if (Account.setOrVerify(recipientAccount.getId(), publicKey)) {
                recipientAccount.apply(this.publicKey);
            }
        }

        @Override
        boolean isPhasable() {
            return false;
        }

        public byte[] getPublicKey() {
            return publicKey;
        }

    }

}