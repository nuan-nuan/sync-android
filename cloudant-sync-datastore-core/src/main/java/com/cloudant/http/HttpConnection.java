//  Copyright (c) 2015 IBM Cloudant. All rights reserved.
//
//  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
//  except in compliance with the License. You may obtain a copy of the License at
//  http://www.apache.org/licenses/LICENSE-2.0
//  Unless required by applicable law or agreed to in writing, software distributed under the
//  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
//  either express or implied. See the License for the specific language governing permissions
//  and limitations under the License.

package com.cloudant.http;

import com.cloudant.android.Base64OutputStreamFactory;
import com.cloudant.sync.util.Misc;
import com.google.common.io.Resources;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;


/**
 * Created by tomblench on 23/03/15.
 */

/**
 * <p>
 * A wrapper for <code>HttpURLConnection</code>s.
 * </p>
 *
 * <p>
 * Provides some convenience methods for making requests and sending/receiving data as streams,
 * strings, or byte arrays.
 * </p>
 *
 * <p>
 * Typical usage:
 * </p>
 *
 * <pre>
 * HttpConnection hc = new HttpConnection("POST", "application/json", new URL("http://somewhere"));
 * hc.requestProperties.put("x-some-header", "some-value");
 * hc.setRequestBody("{\"hello\": \"world\"});
 * String result = hc.execute().responseAsString();
 * // get the underlying HttpURLConnection if you need to do something a bit more advanced:
 * int response = hc.getConnection().getResponseCode();
 * hc.disconnect();
 * </pre>
 *
 * <p>
 * <b>Important:</b> this class is not thread-safe and <code>HttpConnection</code>s should not be
 * shared across threads.
 * </p>
 *
 * @see java.net.HttpURLConnection
 */
public class HttpConnection  {

    private static final Logger logger = Logger.getLogger(HttpConnection.class.getCanonicalName());
    private final String requestMethod;
    public final URL url;
    private final String contentType;

    // created in executeInternal
    private HttpURLConnection connection;

    // set by the various setRequestBody() methods
    private InputStreamGenerator input;
    private long inputLength;

    public final HashMap<String, String> requestProperties;

    private static String userAgent = getUserAgent();

    public final List<HttpConnectionRequestInterceptor> requestInterceptors;
    public final List<HttpConnectionResponseInterceptor> responseInterceptors;

    private int numberOfRetries = 10;


    public HttpConnection(String requestMethod,
                          URL url,
                          String contentType) {
        this.requestMethod = requestMethod;
        this.url = url;
        this.contentType = contentType;
        this.requestProperties = new HashMap<String, String>();
        this.requestInterceptors = new LinkedList<HttpConnectionRequestInterceptor>();
        this.responseInterceptors = new LinkedList<HttpConnectionResponseInterceptor>();
    }

    /**
     * Sets the number of times this request can be retried.
     * This method <strong>must</strong> be called before {@link #execute()}
     * @param numberOfRetries the number of times this request can be retried.
     * @return an {@link HttpConnection} for method chaining 
     */
    public HttpConnection setNumberOfRetries(int numberOfRetries){
        this.numberOfRetries = numberOfRetries;
        return this;
    }

    /**
     * Set the String of request body data to be sent to the server.
     * @param input String of request body data to be sent to the server.
     *              The input is assumed to be UTF-8 encoded.
     * @return an {@link HttpConnection} for method chaining 
     */
    public HttpConnection setRequestBody(final String input) {
        try {
            final byte[] bytes = input.getBytes("UTF-8");
            // input is in bytes, not characters
            this.inputLength = bytes.length;
            this.input = new InputStreamGenerator() {
                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }
            };
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }

        return this;
    }

    /**
     * Set the byte array of request body data to be sent to the server.
     * @param input byte array of request body data to be sent to the server
     * @return an {@link HttpConnection} for method chaining 
     */
    public HttpConnection setRequestBody(final byte[] input) {
        this.input = new InputStreamGenerator() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(input);
            }
        };
        this.inputLength = input.length;
        return this;
    }

    /**
     * Set the InputStream of request body data to be sent to the server.
     * @param input InputStream of request body data to be sent to the server
     * @return an {@link HttpConnection} for method chaining 
     */
    public HttpConnection setRequestBody(InputStreamGenerator input) {
        this.input = input;
        // -1 signals inputLength unknown
        this.inputLength = -1;
        return this;
    }

    /**
     * Set the InputStream of request body data, of known length, to be sent to the server.
     * @param input InputStream of request body data to be sent to the server
     * @param inputLength Length of request body data to be sent to the server, in bytes
     * @return an {@link HttpConnection} for method chaining 
     */
    public HttpConnection setRequestBody(InputStreamGenerator input, long inputLength) {
        this.input = input;
        this.inputLength = inputLength;
        return this;
    }

    /**
     * <p>
     * Execute request without returning data from server.
     * </p>
     * <p>
     * Call {@code responseAsString}, {@code responseAsBytes}, or {@code responseAsInputStream}
     * after {@code execute} if the response body is required.
     * </p>
     * @return An {@link HttpConnection} which can be used to obtain the response body
     * @throws IOException if there was a problem writing data to the server
     */
    public HttpConnection execute() throws IOException {
            boolean retry = true;
            int n = numberOfRetries;
            while (retry && n-- > 0) {

                System.setProperty("http.keepAlive", "false");

                connection = (HttpURLConnection) url.openConnection();
                for (String key : requestProperties.keySet()) {
                    connection.setRequestProperty(key, requestProperties.get(key));
                }

                connection.setRequestProperty("User-Agent", userAgent);
                if (url.getUserInfo() != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    OutputStream bos = Base64OutputStreamFactory.get(baos);
                    bos.write(url.getUserInfo().getBytes());
                    bos.flush();
                    bos.close();
                    String encodedAuth = baos.toString();
                    connection.setRequestProperty("Authorization", String.format("Basic %s", encodedAuth));
                }


                // always read the result, so we can retrieve the HTTP response code
                connection.setDoInput(true);
                connection.setRequestMethod(requestMethod);
                if (contentType != null) {
                    connection.setRequestProperty("Content-type", contentType);
                }

                HttpConnectionInterceptorContext currentContext = new HttpConnectionInterceptorContext(this);

                for (HttpConnectionRequestInterceptor requestInterceptor : requestInterceptors) {
                    currentContext = requestInterceptor.interceptRequest(currentContext);
                }

                if (input != null) {
                    connection.setDoOutput(true);
                    if (inputLength != -1) {
                        // TODO on 1.7 upwards this method takes a long, otherwise int
                        connection.setFixedLengthStreamingMode((int) this.inputLength);
                    } else {
                        // TODO some situations where we can't do chunking, like multipart/related
                        /// https://issues.apache.org/jira/browse/COUCHDB-1403
                        connection.setChunkedStreamingMode(1024);
                    }

                    // See "8.2.3 Use of the 100 (Continue) Status" in http://tools.ietf.org/html
                    // /rfc2616
                    // Attempting to write to the connection's OutputStream may cause an exception to be
                    // thrown. This is useful because it avoids sending large request bodies (such as
                    // attachments) if the server is going to reject our request. Reasons for rejecting
                    // requests could be 401 Unauthorized (eg cookie needs to be refreshed), etc.
                    connection.setRequestProperty("Expect", "100-continue");

                    int bufSize = 1024;
                    int nRead = 0;
                    byte[] buf = new byte[bufSize];
                    InputStream is = input.getInputStream();
                    OutputStream os = connection.getOutputStream();

                    while ((nRead = is.read(buf)) >= 0) {
                        os.write(buf, 0, nRead);
                    }
                    os.flush();
                    // we do not call os.close() - on some JVMs this incurs a delay of several seconds
                    // see http://stackoverflow.com/questions/19860436
                }

                for (HttpConnectionResponseInterceptor responseInterceptor : responseInterceptors) {
                    currentContext = responseInterceptor.interceptResponse(currentContext);
                }

                // retry flag is set from the final step in the response interceptRequest pipeline
                retry = currentContext.replayRequest;

                if (n == 0) {
                    logger.info("Maximum number of retries reached");
                }
            }
            // return ourselves to allow method chaining
            return this;
        }

    /**
     * <p>
     * Return response body data from server as a String.
     * </p>
     * <p>
     * <b>Important:</b> you must call <code>execute()</code> before calling this method.
     * </p>
     * @return String of response body data from server, if any
     * @throws IOException if there was a problem reading data from the server
     */
    public String responseAsString() throws IOException {
        if (connection == null) {
            throw new IOException("Attempted to read response from server before calling execute()");
        }
        InputStream is = connection.getInputStream();
        String string = IOUtils.toString(is);
        is.close();
        connection.disconnect();
        return string;
    }

    /**
     * <p>
     * Return response body data from server as a byte array.
     * </p>
     * <p>
     * <b>Important:</b> you must call <code>execute()</code> before calling this method.
     * </p>
     * @return Byte array of response body data from server, if any
     * @throws IOException if there was a problem reading data from the server
     */
    public byte[] responseAsBytes() throws IOException {
        if (connection == null) {
            throw new IOException("Attempted to read response from server before calling execute()");
        }
        InputStream is = connection.getInputStream();
        byte[] bytes = IOUtils.toByteArray(is);
        is.close();
        connection.disconnect();
        return bytes;
    }

    /**
     * <p>
     * Return response body data from server as an InputStream.
     * </p>
     * <p>
     * <b>Important:</b> you must call <code>execute()</code> before calling this method.
     * </p>
     * @return InputStream of response body data from server, if any
     * @throws IOException if there was a problem reading data from the server
     */
    public InputStream responseAsInputStream() throws IOException {
        if (connection == null) {
            throw new IOException("Attempted to read response from server before calling execute()");
        }
        InputStream is = connection.getInputStream();
        return is;
    }

    /**
     * Get the underlying HttpURLConnection object, allowing clients to set/get properties not
     * exposed here.
     * @return HttpURLConnection the underlying {@link HttpURLConnection} object
     */
    public HttpURLConnection getConnection() {
        return connection;
    }

    /**
     * Disconnect the underlying HttpURLConnection. Equivalent to calling:
     * <code>
     * getConnection.disconnect()
     * </code>
     */
    public void disconnect() {
        connection.disconnect();
    }

    private static String getUserAgent() {
        String userAgent;
        String ua = getUserAgentFromResource();
        if (Misc.isRunningOnAndroid()) {
            try {
                Class c = Class.forName("android.os.Build$VERSION");
                String codename = (String) c.getField("CODENAME").get(null);
                int sdkInt = c.getField("SDK_INT").getInt(null);
                userAgent = String.format("%s Android %s %d", ua, codename, sdkInt);
            } catch (Exception e) {
                userAgent = String.format("%s Android unknown version", ua);
            }
        } else {
            userAgent = String.format("%s Java (%s; %s; %s)",
                    ua,
                    System.getProperty("os.arch"),
                    System.getProperty("os.name"),
                    System.getProperty("os.version"));
        }
        return userAgent;
    }

    private static String getUserAgentFromResource() {
        final String defaultUserAgent = "CloudantSync";
        final URL url = HttpConnection.class.getClassLoader().getResource("mazha.properties");
        final Properties properties = new Properties();
        try {
            properties.load(Resources.newInputStreamSupplier(url).getInput());
            return properties.getProperty("user.agent", defaultUserAgent);
        } catch (Exception ex) {
            return defaultUserAgent;
        }
    }

    public interface InputStreamGenerator
    {
        InputStream getInputStream();
    }

}
