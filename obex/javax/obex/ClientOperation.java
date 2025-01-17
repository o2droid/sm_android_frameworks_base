/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package javax.obex;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * This class implements the <code>Operation</code> interface. It will read and
 * write data via puts and gets.
 * @hide
 */
public final class ClientOperation implements Operation, BaseStream {

    private ClientSession mParent;

    private boolean mInputOpen;

    private PrivateInputStream mPrivateInput;

    private boolean mPrivateInputOpen;

    private PrivateOutputStream mPrivateOutput;

    private boolean mPrivateOutputOpen;

    private String mExceptionMessage;

    private int mMaxPacketSize;

    private boolean mOperationDone;

    private boolean mGetOperation;

    private HeaderSet mRequestHeader;

    private HeaderSet mReplyHeader;

    private boolean mEndOfBodySent;

    private ObexByteBuffer mOutBuffer;

    /**
     * Creates new OperationImpl to read and write data to a server
     * @param maxSize the maximum packet size
     * @param p the parent to this object
     * @param type <code>true</code> if this is a get request;
     *        <code>false</code. if this is a put request
     * @param header the header to set in the initial request
     * @throws IOException if the an IO error occurred
     */
    public ClientOperation(int maxSize, ClientSession p, HeaderSet header, boolean type)
            throws IOException {

        mParent = p;
        mEndOfBodySent = false;
        mInputOpen = true;
        mOperationDone = false;
        mMaxPacketSize = maxSize;
        mGetOperation = type;

        mPrivateInputOpen = false;
        mPrivateOutputOpen = false;
        mPrivateInput = null;
        mPrivateOutput = null;

        mReplyHeader = new HeaderSet();

        mRequestHeader = new HeaderSet();

	mOutBuffer = new ObexByteBuffer(32);

        int[] headerList = header.getHeaderList();

        if (headerList != null) {

            for (int i = 0; i < headerList.length; i++) {
                mRequestHeader.setHeader(headerList[i], header.getHeader(headerList[i]));
            }
        }

        if ((header).mAuthChall != null) {
            mRequestHeader.mAuthChall = new byte[(header).mAuthChall.length];
            System.arraycopy((header).mAuthChall, 0, mRequestHeader.mAuthChall, 0,
                    (header).mAuthChall.length);
        }

        if ((header).mAuthResp != null) {
            mRequestHeader.mAuthResp = new byte[(header).mAuthResp.length];
            System.arraycopy((header).mAuthResp, 0, mRequestHeader.mAuthResp, 0,
                    (header).mAuthResp.length);

        }
    }

    /**
     * Sends an ABORT message to the server. By calling this method, the
     * corresponding input and output streams will be closed along with this
     * object.
     * @throws IOException if the transaction has already ended or if an OBEX
     *         server called this method
     */
    public synchronized void abort() throws IOException {
        ensureOpen();
        //no compatible with sun-ri
        if ((mOperationDone) && (mReplyHeader.responseCode != ResponseCodes.OBEX_HTTP_CONTINUE)) {
            throw new IOException("Operation has already ended");
        }

        mExceptionMessage = "Operation aborted";
        if ((!mOperationDone) && (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE)) {
            mOperationDone = true;
            /*
             * Since we are not sending any headers or returning any headers then
             * we just need to write and read the same bytes
             */
            mParent.sendRequest(ObexHelper.OBEX_OPCODE_ABORT, null, mReplyHeader, null);

            if (mReplyHeader.responseCode != ResponseCodes.OBEX_HTTP_OK) {
                throw new IOException("Invalid response code from server");
            }

            mExceptionMessage = null;
        }

        close();
    }

    /**
     * Retrieves the response code retrieved from the server. Response codes are
     * defined in the <code>ResponseCodes</code> interface.
     * @return the response code retrieved from the server
     * @throws IOException if an error occurred in the transport layer during
     *         the transaction; if this method is called on a
     *         <code>HeaderSet</code> object created by calling
     *         <code>createHeaderSet</code> in a <code>ClientSession</code>
     *         object
     */
    public synchronized int getResponseCode() throws IOException {
        //avoid dup validateConnection
        if ((mReplyHeader.responseCode == -1)
                || (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE)) {
            validateConnection();
        }

        return mReplyHeader.responseCode;
    }

    /**
     * This method will always return <code>null</code>
     * @return <code>null</code>
     */
    public String getEncoding() {
        return null;
    }

    /**
     * Returns the type of content that the resource connected to is providing.
     * E.g. if the connection is via HTTP, then the value of the content-type
     * header field is returned.
     * @return the content type of the resource that the URL references, or
     *         <code>null</code> if not known
     */
    public String getType() {
        try {
            return (String)mReplyHeader.getHeader(HeaderSet.TYPE);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the length of the content which is being provided. E.g. if the
     * connection is via HTTP, then the value of the content-length header field
     * is returned.
     * @return the content length of the resource that this connection's URL
     *         references, or -1 if the content length is not known
     */
    public long getLength() {
        try {
            Long temp = (Long)mReplyHeader.getHeader(HeaderSet.LENGTH);

            if (temp == null) {
                return -1;
            } else {
                return temp.longValue();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Open and return an input stream for a connection.
     * @return an input stream
     * @throws IOException if an I/O error occurs
     */
    public InputStream openInputStream() throws IOException {

        ensureOpen();

        if (mPrivateInputOpen)
            throw new IOException("no more input streams available");
        if (mGetOperation) {
            // send the GET request here
            validateConnection();
        } else {
            if (mPrivateInput == null) {
                mPrivateInput = new PrivateInputStream(this);
            }
        }

        mPrivateInputOpen = true;

        return mPrivateInput;
    }

    /**
     * Open and return a data input stream for a connection.
     * @return an input stream
     * @throws IOException if an I/O error occurs
     */
    public DataInputStream openDataInputStream() throws IOException {
        return new DataInputStream(openInputStream());
    }

    /**
     * Open and return an output stream for a connection.
     * @return an output stream
     * @throws IOException if an I/O error occurs
     */
    public OutputStream openOutputStream() throws IOException {

        ensureOpen();
        ensureNotDone();

        if (mPrivateOutputOpen)
            throw new IOException("no more output streams available");

        if (mPrivateOutput == null) {
            // there are 3 bytes operation headers and 3 bytes body headers //
            mPrivateOutput = new PrivateOutputStream(this, getMaxPacketSize());
        }

        mPrivateOutputOpen = true;

        return mPrivateOutput;
    }

    public int getMaxPacketSize() {
        return mMaxPacketSize - 6 - getHeaderLength();
    }

    public int getHeaderLength() {
        // OPP may need it
        byte[] headerArray = ObexHelper.createHeader(mRequestHeader, false);
        return headerArray.length;
    }

    /**
     * Open and return a data output stream for a connection.
     * @return an output stream
     * @throws IOException if an I/O error occurs
     */
    public DataOutputStream openDataOutputStream() throws IOException {
        return new DataOutputStream(openOutputStream());
    }

    /**
     * Closes the connection and ends the transaction
     * @throws IOException if the operation has already ended or is closed
     */
    public void close() throws IOException {
        mInputOpen = false;
        mPrivateInputOpen = false;
        mPrivateOutputOpen = false;
        mParent.setRequestInactive();
    }

    /**
     * Returns the headers that have been received during the operation.
     * Modifying the object returned has no effect on the headers that are sent
     * or retrieved.
     * @return the headers received during this <code>Operation</code>
     * @throws IOException if this <code>Operation</code> has been closed
     */
    public HeaderSet getReceivedHeader() throws IOException {
        ensureOpen();

        return mReplyHeader;
    }

    /**
     * Specifies the headers that should be sent in the next OBEX message that
     * is sent.
     * @param headers the headers to send in the next message
     * @throws IOException if this <code>Operation</code> has been closed or the
     *         transaction has ended and no further messages will be exchanged
     * @throws IllegalArgumentException if <code>headers</code> was not created
     *         by a call to <code>ServerRequestHandler.createHeaderSet()</code>
     * @throws NullPointerException if <code>headers</code> is <code>null</code>
     */
    public void sendHeaders(HeaderSet headers) throws IOException {
        ensureOpen();
        if (mOperationDone) {
            throw new IOException("Operation has already exchanged all data");
        }

        if (headers == null) {
            throw new IOException("Headers may not be null");
        }

        int[] headerList = headers.getHeaderList();
        if (headerList != null) {
            for (int i = 0; i < headerList.length; i++) {
                mRequestHeader.setHeader(headerList[i], headers.getHeader(headerList[i]));
            }
        }
    }

    /**
     * Verifies that additional information may be sent. In other words, the
     * operation is not done.
     * @throws IOException if the operation is completed
     */
    public void ensureNotDone() throws IOException {
        if (mOperationDone) {
            throw new IOException("Operation has completed");
        }
    }

    /**
     * Verifies that the connection is open and no exceptions should be thrown.
     * @throws IOException if an exception needs to be thrown
     */
    public void ensureOpen() throws IOException {
        mParent.ensureOpen();

        if (mExceptionMessage != null) {
            throw new IOException(mExceptionMessage);
        }
        if (!mInputOpen) {
            throw new IOException("Operation has already ended");
        }
    }

    /**
     * Verifies that the connection is open and the proper data has been read.
     * @throws IOException if an IO error occurs
     */
    private void validateConnection() throws IOException {
        ensureOpen();

        // to sure only one privateInput object exist.
        if (mPrivateInput == null) {
            startProcessing();
        }
    }

    /**
     * Sends a request to the client of the specified type
     * @param opCode the request code to send to the client
     * @return <code>true</code> if there is more data to send;
     *         <code>false</code> if there is no more data to send
     * @throws IOException if an IO error occurs
     */
    private boolean sendRequest(int opCode) throws IOException {
        boolean returnValue = false;
        int bodyLength = -1;
        byte[] headerArray = ObexHelper.createHeader(mRequestHeader, true);
        if (mPrivateOutput != null) {
            bodyLength = mPrivateOutput.size();
        }

        /*
         * Determine if there is space to add a body request.  At present
         * this method checks to see if there is room for at least a 17
         * byte body header.  This number needs to be at least 6 so that
         * there is room for the header ID and length and the reply ID and
         * length, but it is a waste of resources if we can't send much of
         * the body.
         */
        if ((ObexHelper.BASE_PACKET_LENGTH + headerArray.length) > mMaxPacketSize) {
            int end = 0;
            int start = 0;
            // split & send the headerArray in multiple packets.

            while (end != headerArray.length) {
                //split the headerArray
                end = ObexHelper.findHeaderEnd(headerArray, start, mMaxPacketSize
                        - ObexHelper.BASE_PACKET_LENGTH);
                // can not split 
                if (end == -1) {
                    mOperationDone = true;
                    abort();
                    mExceptionMessage = "Header larger then can be sent in a packet";
                    mInputOpen = false;

                    if (mPrivateInput != null) {
                        mPrivateInput.close();
                    }

                    if (mPrivateOutput != null) {
                        mPrivateOutput.close();
                    }
                    throw new IOException("OBEX Packet exceeds max packet size");
                }

                mOutBuffer.reset();
                mOutBuffer.write(headerArray, start, end - start);
                if (!mParent.sendRequest(opCode, mOutBuffer, mReplyHeader, mPrivateInput)) {
                    return false;
                }

                if (mReplyHeader.responseCode != ResponseCodes.OBEX_HTTP_CONTINUE) {
                    return false;
                }

                start = end;
            }

            if (bodyLength > 0) {
                return true;
            } else {
                return false;
            }
        } else {
            mOutBuffer.reset();
            mOutBuffer.write(headerArray);
        }

        if (bodyLength > 0) {
            /*
             * Determine if we can send the whole body or just part of
             * the body.  Remember that there is the 3 bytes for the
             * response message and 3 bytes for the header ID and length
             */
            if (bodyLength > (mMaxPacketSize - headerArray.length - 6)) {
                returnValue = true;

                bodyLength = mMaxPacketSize - headerArray.length - 6;
            }

            /*
             * Since this is a put request if the final bit is set or
             * the output stream is closed we need to send the 0x49
             * (End of Body) otherwise, we need to send 0x48 (Body)
             */
            if ((mPrivateOutput.isClosed()) && (!returnValue) && (!mEndOfBodySent)
                    && ((opCode & 0x80) != 0)) {
		mOutBuffer.write((byte)0x49);
                mEndOfBodySent = true;
            } else {
		mOutBuffer.write((byte)0x48);
            }

            bodyLength += 3;
            mOutBuffer.write((byte)(bodyLength >> 8));
            mOutBuffer.write((byte)bodyLength);
            mPrivateOutput.writeTo(mOutBuffer, bodyLength - 3);
        }

        if (mPrivateOutputOpen && bodyLength <= 0 && !mEndOfBodySent) {
            // only 0x82 or 0x83 can send 0x49
            if ((opCode & 0x80) == 0) {
		mOutBuffer.write((byte)0x48);
            } else {
		mOutBuffer.write((byte)0x49);
                mEndOfBodySent = true;
            }

            bodyLength = 3;
            mOutBuffer.write((byte)(bodyLength >> 8));
            mOutBuffer.write((byte)bodyLength);
        }

	if (mOutBuffer.getLength() == 0) {
            if (!mParent.sendRequest(opCode, null, mReplyHeader, mPrivateInput)) {
                return false;
            }
            return returnValue;
        }
        if ((mOutBuffer.getLength() > 0)
                && (!mParent.sendRequest(opCode, mOutBuffer, mReplyHeader, mPrivateInput))) {
            return false;
        }

        // send all of the output data in 0x48, 
        // send 0x49 with empty body
        if ((mPrivateOutput != null) && (mPrivateOutput.size() > 0))
            returnValue = true;

        return returnValue;
    }

    /**
     * This method starts the processing thread results. It will send the
     * initial request. If the response takes more then one packet, a thread
     * will be started to handle additional requests
     * @throws IOException if an IO error occurs
     */
    private synchronized void startProcessing() throws IOException {

        if (mPrivateInput == null) {
            mPrivateInput = new PrivateInputStream(this);
        }
        boolean more = true;

        if (mGetOperation) {
            if (!mOperationDone) {
                mReplyHeader.responseCode = ResponseCodes.OBEX_HTTP_CONTINUE;
                while ((more) && (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE)) {
                    more = sendRequest(0x03);
                }

                if (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE) {
                    mParent.sendRequest(0x83, null, mReplyHeader, mPrivateInput);
                }
                if (mReplyHeader.responseCode != ResponseCodes.OBEX_HTTP_CONTINUE) {
                    mOperationDone = true;
                }
            }
        } else {

            if (!mOperationDone) {
                mReplyHeader.responseCode = ResponseCodes.OBEX_HTTP_CONTINUE;
                while ((more) && (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE)) {
                    more = sendRequest(0x02);

                }
            }

            if (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE) {
                mParent.sendRequest(0x82, null, mReplyHeader, mPrivateInput);
            }

            if (mReplyHeader.responseCode != ResponseCodes.OBEX_HTTP_CONTINUE) {
                mOperationDone = true;
            }
        }
    }

    /**
     * Continues the operation since there is no data to read.
     * @param sendEmpty <code>true</code> if the operation should send an empty
     *        packet or not send anything if there is no data to send
     * @param inStream <code>true</code> if the stream is input stream or is
     *        output stream
     * @throws IOException if an IO error occurs
     */
    public synchronized boolean continueOperation(boolean sendEmpty, boolean inStream)
            throws IOException {

        if (mGetOperation) {
            if ((inStream) && (!mOperationDone)) {
                // to deal with inputstream in get operation
                mParent.sendRequest(0x83, null, mReplyHeader, mPrivateInput);
                /*
                  * Determine if that was not the last packet in the operation
                  */
                if (mReplyHeader.responseCode != ResponseCodes.OBEX_HTTP_CONTINUE) {
                    mOperationDone = true;
                }

                return true;

            } else if ((!inStream) && (!mOperationDone)) {
                // to deal with outputstream in get operation

                if (mPrivateInput == null) {
                    mPrivateInput = new PrivateInputStream(this);
                }
                sendRequest(0x03);
                return true;

            } else if (mOperationDone) {
                return false;
            }

        } else {
            if ((!inStream) && (!mOperationDone)) {
                // to deal with outputstream in put operation
                if (mReplyHeader.responseCode == -1) {
                    mReplyHeader.responseCode = ResponseCodes.OBEX_HTTP_CONTINUE;
                }
                sendRequest(0x02);
                return true;
            } else if ((inStream) && (!mOperationDone)) {
                // How to deal with inputstream  in put operation ?
                return false;

            } else if (mOperationDone) {
                return false;
            }

        }
        return false;
    }

    /**
     * Called when the output or input stream is closed.
     * @param inStream <code>true</code> if the input stream is closed;
     *        <code>false</code> if the output stream is closed
     * @throws IOException if an IO error occurs
     */
    public void streamClosed(boolean inStream) throws IOException {
        if (!mGetOperation) {
            if ((!inStream) && (!mOperationDone)) {
                // to deal with outputstream in put operation

                boolean more = true;

                if ((mPrivateOutput != null) && (mPrivateOutput.size() <= 0)) {
                    byte[] headerArray = ObexHelper.createHeader(mRequestHeader, false);
                    if (headerArray.length <= 0)
                        more = false;
                }
                // If have not sent any data so send  all now
                if (mReplyHeader.responseCode == -1) {
                    mReplyHeader.responseCode = ResponseCodes.OBEX_HTTP_CONTINUE;
                }

                while ((more) && (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE)) {
                    more = sendRequest(0x02);
                }

                /*
                 * According to the IrOBEX specification, after the final put, you
                 * only have a single reply to send.  so we don't need the while
                 * loop.
                 */
                while (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE) {

                    sendRequest(0x82);
                }
                mOperationDone = true;
            } else if ((inStream) && (mOperationDone)) {
                // how to deal with input stream in put stream ?
                mOperationDone = true;
            }
        } else {
            if ((inStream) && (!mOperationDone)) {

                // to deal with inputstream in get operation
                // Have not sent any data so send it all now

                if (mReplyHeader.responseCode == -1) {
                    mReplyHeader.responseCode = ResponseCodes.OBEX_HTTP_CONTINUE;
                }

                while (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE) {
                    if (!sendRequest(0x83)) {
                        break;
                    }
                }
                while (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE) {
                    mParent.sendRequest(0x83, null, mReplyHeader, mPrivateInput);
                }
                mOperationDone = true;
            } else if ((!inStream) && (!mOperationDone)) {
                // to deal with outputstream in get operation
                // part of the data may have been sent in continueOperation.

                boolean more = true;

                if ((mPrivateOutput != null) && (mPrivateOutput.size() <= 0)) {
                    byte[] headerArray = ObexHelper.createHeader(mRequestHeader, false);
                    if (headerArray.length <= 0)
                        more = false;
                }

                if (mPrivateInput == null) {
                    mPrivateInput = new PrivateInputStream(this);
                }
                if ((mPrivateOutput != null) && (mPrivateOutput.size() <= 0))
                    more = false;

                mReplyHeader.responseCode = ResponseCodes.OBEX_HTTP_CONTINUE;
                while ((more) && (mReplyHeader.responseCode == ResponseCodes.OBEX_HTTP_CONTINUE)) {
                    more = sendRequest(0x03);
                }
                sendRequest(0x83);
                //                parent.sendRequest(0x83, null, replyHeaders, privateInput);
                if (mReplyHeader.responseCode != ResponseCodes.OBEX_HTTP_CONTINUE) {
                    mOperationDone = true;
                }
            }
        }
    }
}
