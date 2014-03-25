package io.hawt.dockerui;

import io.hawt.util.IOHelper;
import io.hawt.util.Strings;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.protocol.DefaultProtocolSocketFactory;
import org.apache.commons.httpclient.protocol.Protocol;
/*
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.apache.commons.httpclient.protocol.Protocol;
*/

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Based on code from http://edwardstx.net/2010/06/http-proxy-servlet/
 */
public class ProxyServlet extends HttpServlet {

    /**
     * Serialization UID.
     */
    private static final long serialVersionUID = 1L;
    /**
     * Key for redirect location header.
     */
    private static final String STRING_LOCATION_HEADER = "Location";
    /**
     * Key for content type header.
     */
    private static final String STRING_CONTENT_TYPE_HEADER_NAME = "Content-Type";

    /**
     * Key for content length header.
     */
    private static final String STRING_CONTENT_LENGTH_HEADER_NAME = "Content-Length";

    private static final String[] IGNORE_HEADER_NAMES = {STRING_CONTENT_LENGTH_HEADER_NAME, "Origin", "Authorization"};


    /**
     * Key for host header
     */
    private static final String STRING_HOST_HEADER_NAME = "Host";

    public static final String DEFAULT_HOST_AND_PORT = "http://localhost:4243";

    public static final String DEFAULT_SOCKET_PATH = "/var/run/docker.sock";

    /**
     * The maximum size for uploaded files in bytes. Default value is 5MB.
     */
    private int intMaxFileUploadSize = 5 * 1024 * 1024;

    private ServletContext servletContext;

    private String hostAndPort = DEFAULT_HOST_AND_PORT;

    /**
     * Initialize the <code>ProxyServlet</code>
     *
     * @param servletConfig The Servlet configuration passed in by the servlet container
     */
    public void init(ServletConfig servletConfig) {
        this.servletContext = servletConfig.getServletContext();
/*


        if (LOG.isDebugEnabled())  {
            LOG.debug("Registered OpenShiftProtocolSocketFactory Protocol for http: " + Protocol.getProtocol("http").getSocketFactory());
        }
*/

        boolean useSocket = false;
        String dockerHost = System.getenv("DOCKER_HOST");
        if (Strings.isBlank(dockerHost)) {
            dockerHost = DEFAULT_HOST_AND_PORT;
        }
        hostAndPort = dockerHost;
        if (hostAndPort.startsWith("tcp:")) {
            hostAndPort = "http:" + hostAndPort.substring(4);
        }
        String socketPath = DEFAULT_SOCKET_PATH;
        if (useSocket) {
            servletContext.log("Using docker socket : " + socketPath);

            UnixSocketFactory socketFactory = new UnixSocketFactory(socketPath);
            Protocol http = new Protocol("http", socketFactory, 80);
            Protocol.registerProtocol("http", http);


        } else {
            servletContext.log("Using docker URL: " + hostAndPort);
        }
    }

    /**
     * Performs an HTTP GET request
     *
     * @param httpServletRequest  The {@link HttpServletRequest} object passed
     *                            in by the servlet engine representing the
     *                            client request to be proxied
     * @param httpServletResponse The {@link HttpServletResponse} object by which
     *                            we can send a proxied response to the client
     */
    public void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws IOException, ServletException {
        // Create a GET request
        ProxyDetails proxyDetails = new ProxyDetails(hostAndPort, httpServletRequest, servletContext);
        if (!proxyDetails.isValid()) {
            httpServletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "Context-Path should contain the proxy hostname to use");
        } else {
            GetMethod getMethodProxyRequest = new GetMethod(proxyDetails.getStringProxyURL());
            // Forward the request headers
            setProxyRequestHeaders(proxyDetails, httpServletRequest, getMethodProxyRequest);
            // Execute the proxy request
            this.executeProxyRequest(proxyDetails, getMethodProxyRequest, httpServletRequest, httpServletResponse);
        }
    }

    /**
     * Performs an HTTP POST request
     *
     * @param httpServletRequest  The {@link HttpServletRequest} object passed
     *                            in by the servlet engine representing the
     *                            client request to be proxied
     * @param httpServletResponse The {@link HttpServletResponse} object by which
     *                            we can send a proxied response to the client
     */
    public void doPost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws IOException, ServletException {
        // Create a standard POST request
        ProxyDetails proxyDetails = new ProxyDetails(hostAndPort, httpServletRequest, servletContext);
        if (!proxyDetails.isValid()) {
            httpServletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "Context-Path should contain the proxy hostname to use");
        } else {
            PostMethod postMethodProxyRequest = new PostMethod(proxyDetails.getStringProxyURL());
            // Forward the request headers
            setProxyRequestHeaders(proxyDetails, httpServletRequest, postMethodProxyRequest);
/*
            // Check if this is a mulitpart (file upload) POST
            if (ServletFileUpload.isMultipartContent(httpServletRequest)) {
                this.handleMultipartPost(postMethodProxyRequest, httpServletRequest);
            } else {
                this.handleStandardPost(postMethodProxyRequest, httpServletRequest);
            }
*/
            this.handleStandardPost(postMethodProxyRequest, httpServletRequest);

            // Execute the proxy request
            this.executeProxyRequest(proxyDetails, postMethodProxyRequest, httpServletRequest, httpServletResponse);
        }
    }

    /**
     * Sets up the given {@link org.apache.commons.httpclient.methods.PostMethod} to send the same multipart POST
     * data as was sent in the given {@link HttpServletRequest}
     *
     * @param postMethodProxyRequest The {@link org.apache.commons.httpclient.methods.PostMethod} that we are
     *                               configuring to send a multipart POST request
     * @param httpServletRequest     The {@link HttpServletRequest} that contains
     *                               the mutlipart POST data to be sent via the {@link org.apache.commons.httpclient.methods.PostMethod}
     *//*
    private void handleMultipartPost(PostMethod postMethodProxyRequest, HttpServletRequest httpServletRequest)
            throws ServletException {
        // Create a factory for disk-based file items
        DiskFileItemFactory diskFileItemFactory = new DiskFileItemFactory();
        // Set factory constraints
        diskFileItemFactory.setSizeThreshold(this.getMaxFileUploadSize());
        diskFileItemFactory.setRepository(FILE_UPLOAD_TEMP_DIRECTORY);
        // Create a new file upload handler
        ServletFileUpload servletFileUpload = new ServletFileUpload(diskFileItemFactory);
        // Parse the request
        try {
            // Get the multipart items as a list
            List<FileItem> listFileItems = (List<FileItem>) servletFileUpload.parseRequest(httpServletRequest);
            // Create a list to hold all of the parts
            List<Part> listParts = new ArrayList<Part>();
            // Iterate the multipart items list
            for (FileItem fileItemCurrent : listFileItems) {
                // If the current item is a form field, then create a string part
                if (fileItemCurrent.isFormField()) {
                    StringPart stringPart = new StringPart(
                            fileItemCurrent.getFieldName(), // The field name
                            fileItemCurrent.getString()     // The field value
                    );
                    // Add the part to the list
                    listParts.add(stringPart);
                } else {
                    // The item is a file upload, so we create a FilePart
                    FilePart filePart = new FilePart(
                            fileItemCurrent.getFieldName(),    // The field name
                            new ByteArrayPartSource(
                                    fileItemCurrent.getName(), // The uploaded file name
                                    fileItemCurrent.get()      // The uploaded file contents
                            )
                    );
                    // Add the part to the list
                    listParts.add(filePart);
                }
            }
            MultipartRequestEntity multipartRequestEntity = new MultipartRequestEntity(
                    listParts.toArray(new Part[]{}),
                    postMethodProxyRequest.getParams()
            );
            postMethodProxyRequest.setRequestEntity(multipartRequestEntity);
            // The current content-type header (received from the client) IS of
            // type "multipart/form-data", but the content-type header also
            // contains the chunk boundary string of the chunks. Currently, this
            // header is using the boundary of the client request, since we
            // blindly copied all headers from the client request to the proxy
            // request. However, we are creating a new request with a new chunk
            // boundary string, so it is necessary that we re-set the
            // content-type string to reflect the new chunk boundary string
            postMethodProxyRequest.setRequestHeader(STRING_CONTENT_TYPE_HEADER_NAME, multipartRequestEntity.getContentType());
        } catch (FileUploadException fileUploadException) {
            throw new ServletException(fileUploadException);
        }
    }*/

    /**
     * Sets up the given {@link org.apache.commons.httpclient.methods.PostMethod} to send the same standard POST
     * data as was sent in the given {@link HttpServletRequest}
     *
     * @param postMethodProxyRequest The {@link org.apache.commons.httpclient.methods.PostMethod} that we are
     *                               configuring to send a standard POST request
     * @param httpServletRequest     The {@link HttpServletRequest} that contains
     *                               the POST data to be sent via the {@link org.apache.commons.httpclient.methods.PostMethod}
     */
    @SuppressWarnings("unchecked")
    private void handleStandardPost(PostMethod postMethodProxyRequest, HttpServletRequest httpServletRequest) throws IOException {
        // Get the client POST data as a Map
        Map<String, String[]> mapPostParameters = (Map<String, String[]>) httpServletRequest.getParameterMap();
        // Create a List to hold the NameValuePairs to be passed to the PostMethod
        List<NameValuePair> listNameValuePairs = new ArrayList<NameValuePair>();
        // Iterate the parameter names
        for (String stringParameterName : mapPostParameters.keySet()) {
            // Iterate the values for each parameter name
            String[] stringArrayParameterValues = mapPostParameters.get(stringParameterName);
            for (String stringParamterValue : stringArrayParameterValues) {
                // Create a NameValuePair and store in list
                NameValuePair nameValuePair = new NameValuePair(stringParameterName, stringParamterValue);
                listNameValuePairs.add(nameValuePair);
            }
        }
        RequestEntity entity = null;
        String contentType = httpServletRequest.getContentType();
        if (contentType != null) {
            contentType = contentType.toLowerCase();
            if (contentType.contains("json") || contentType.contains("xml") || contentType.contains("application") || contentType.contains("text")) {
                String body = IOHelper.readFully(httpServletRequest.getReader());
                entity = new StringRequestEntity(body, contentType, httpServletRequest.getCharacterEncoding());
                postMethodProxyRequest.setRequestEntity(entity);
            }
        }
        NameValuePair[] parameters = listNameValuePairs.toArray(new NameValuePair[]{});
        if (entity != null) {
            // TODO add as URL parameters?
            //postMethodProxyRequest.addParameters(parameters);
        } else {
            // Set the proxy request POST data
            postMethodProxyRequest.setRequestBody(parameters);
        }
    }

    /**
     * Executes the {@link org.apache.commons.httpclient.HttpMethod} passed in and sends the proxy response
     * back to the client via the given {@link HttpServletResponse}
     *
     * @param proxyDetails
     * @param httpMethodProxyRequest An object representing the proxy request to be made
     * @param httpServletResponse    An object by which we can send the proxied
     *                               response back to the client
     * @throws java.io.IOException      Can be thrown by the {@link org.apache.commons.httpclient.HttpClient}.executeMethod
     * @throws ServletException Can be thrown to indicate that another error has occurred
     */
    private void executeProxyRequest(
            ProxyDetails proxyDetails, HttpMethod httpMethodProxyRequest,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse)
            throws IOException, ServletException {
        httpMethodProxyRequest.setFollowRedirects(false);

        // Create a default HttpClient
        HttpClient httpClient = proxyDetails.createHttpClient(httpMethodProxyRequest);

        // Execute the request
        int intProxyResponseCode = httpClient.executeMethod(httpMethodProxyRequest);

        // Check if the proxy response is a redirect
        // The following code is adapted from org.tigris.noodle.filters.CheckForRedirect
        // Hooray for open source software
        if (intProxyResponseCode >= HttpServletResponse.SC_MULTIPLE_CHOICES /* 300 */
                && intProxyResponseCode < HttpServletResponse.SC_NOT_MODIFIED /* 304 */) {
            String stringStatusCode = Integer.toString(intProxyResponseCode);
            String stringLocation = httpMethodProxyRequest.getResponseHeader(STRING_LOCATION_HEADER).getValue();
            if (stringLocation == null) {
                throw new ServletException("Received status code: " + stringStatusCode
                        + " but no " + STRING_LOCATION_HEADER + " header was found in the response");
            }
            // Modify the redirect to go to this proxy servlet rather that the proxied host
            String stringMyHostName = httpServletRequest.getServerName();
            if (httpServletRequest.getServerPort() != 80) {
                stringMyHostName += ":" + httpServletRequest.getServerPort();
            }
            stringMyHostName += httpServletRequest.getContextPath();
            httpServletResponse.sendRedirect(stringLocation.replace(proxyDetails.getProxyHostAndPort() + proxyDetails.getProxyPath(), stringMyHostName));
            return;
        } else if (intProxyResponseCode == HttpServletResponse.SC_NOT_MODIFIED) {
            // 304 needs special handling.  See:
            // http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304
            // We get a 304 whenever passed an 'If-Modified-Since'
            // header and the data on disk has not changed; server
            // responds w/ a 304 saying I'm not going to send the
            // body because the file has not changed.
            httpServletResponse.setIntHeader(STRING_CONTENT_LENGTH_HEADER_NAME, 0);
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        // Pass the response code back to the client
        httpServletResponse.setStatus(intProxyResponseCode);

        // Pass response headers back to the client
        Header[] headerArrayResponse = httpMethodProxyRequest.getResponseHeaders();
        for (Header header : headerArrayResponse) {
            httpServletResponse.setHeader(header.getName(), header.getValue());
        }

        // Send the content to the client
        InputStream inputStreamProxyResponse = httpMethodProxyRequest.getResponseBodyAsStream();
        BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStreamProxyResponse);
        OutputStream outputStreamClientResponse = httpServletResponse.getOutputStream();
        int intNextByte;
        while ((intNextByte = bufferedInputStream.read()) != -1) {
            outputStreamClientResponse.write(intNextByte);
        }
    }

    public String getServletInfo() {
        return "Jason's Proxy Servlet";
    }

    /**
     * Retrieves all of the headers from the servlet request and sets them on
     * the proxy request
     *
     * @param proxyDetails
     * @param httpServletRequest     The request object representing the client's
     *                               request to the servlet engine
     * @param httpMethodProxyRequest The request that we are about to send to
     */
    private void setProxyRequestHeaders(ProxyDetails proxyDetails, HttpServletRequest httpServletRequest, HttpMethod httpMethodProxyRequest) {
        // Get an Enumeration of all of the header names sent by the client
        Enumeration<?> enumerationOfHeaderNames = httpServletRequest.getHeaderNames();
        while (enumerationOfHeaderNames.hasMoreElements()) {
            String stringHeaderName = (String) enumerationOfHeaderNames.nextElement();

            if (stringHeaderName.equalsIgnoreCase(STRING_CONTENT_LENGTH_HEADER_NAME) ||
                    stringHeaderName.equalsIgnoreCase("Authorization") ||
                    stringHeaderName.equalsIgnoreCase("Origin"))
                continue;
            // As per the Java Servlet API 2.5 documentation:
            //		Some headers, such as Accept-Language can be sent by clients
            //		as several headers each with a different value rather than
            //		sending the header as a comma separated list.
            // Thus, we get an Enumeration of the header values sent by the client
            Enumeration<?> enumerationOfHeaderValues = httpServletRequest.getHeaders(stringHeaderName);
            while (enumerationOfHeaderValues.hasMoreElements()) {
                String stringHeaderValue = (String) enumerationOfHeaderValues.nextElement();
                // In case the proxy host is running multiple virtual servers,
                // rewrite the Host header to ensure that we get content from
                // the correct virtual server
                if (stringHeaderName.equalsIgnoreCase(STRING_HOST_HEADER_NAME)) {
                    stringHeaderValue = proxyDetails.getProxyHostAndPort();
                }
                Header header = new Header(stringHeaderName, stringHeaderValue);
                // Set the same header on the proxy request
                httpMethodProxyRequest.setRequestHeader(header);
            }
        }
    }


    private int getMaxFileUploadSize() {
        return this.intMaxFileUploadSize;
    }

    private void setMaxFileUploadSize(int intMaxFileUploadSizeNew) {
        this.intMaxFileUploadSize = intMaxFileUploadSizeNew;
    }
}