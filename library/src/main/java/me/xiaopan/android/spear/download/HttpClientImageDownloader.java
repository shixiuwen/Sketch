package me.xiaopan.android.spear.download;

import android.util.Log;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HttpContext;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;

import me.xiaopan.android.spear.Spear;
import me.xiaopan.android.spear.request.DownloadRequest;

/**
 * 使用HttpClient来访问网络的下载器
 */
public class HttpClientImageDownloader implements ImageDownloader {
	private static final String NAME = HttpClientImageDownloader.class.getSimpleName();
	public static final int DEFAULT_CONNECTION_TIME_OUT = 15 * 1000;
    public static final int DEFAULT_MAX_CONNECTIONS = 10;
    public static final int DEFAULT_SOCKET_BUFFER_SIZE = 8192;
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 6.0; WOW64) AppleWebKit/534.24 (KHTML, like Gecko) Chrome/11.0.696.16 Safari/534.24";
	private DefaultHttpClient httpClient;
	private Set<String> downloadingFiles;
	private Map<String, ReentrantLock> urlLocks;
    private int maxRetryCount;

	public HttpClientImageDownloader() {
        this.maxRetryCount = 1;
		this.urlLocks = Collections.synchronizedMap(new WeakHashMap<String, ReentrantLock>());
		this.downloadingFiles = Collections.synchronizedSet(new HashSet<String>());
		BasicHttpParams httpParams = new BasicHttpParams();
        ConnManagerParams.setTimeout(httpParams, DEFAULT_CONNECTION_TIME_OUT);
        HttpConnectionParams.setSoTimeout(httpParams, DEFAULT_CONNECTION_TIME_OUT);
        HttpConnectionParams.setConnectionTimeout(httpParams, DEFAULT_CONNECTION_TIME_OUT);
        ConnManagerParams.setMaxConnectionsPerRoute(httpParams, new ConnPerRouteBean(400));
        ConnManagerParams.setMaxTotalConnections(httpParams, DEFAULT_MAX_CONNECTIONS);
        HttpConnectionParams.setSocketBufferSize(httpParams, DEFAULT_SOCKET_BUFFER_SIZE);
        HttpConnectionParams.setTcpNoDelay(httpParams, true);
        HttpProtocolParams.setVersion(httpParams, HttpVersion.HTTP_1_1);
		HttpProtocolParams.setUserAgent(httpParams, DEFAULT_USER_AGENT);	//设置浏览器标识
        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        schemeRegistry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));
        httpClient = new DefaultHttpClient(new ThreadSafeClientConnManager(httpParams, schemeRegistry), httpParams);
        httpClient.addRequestInterceptor(new GzipProcessRequestInterceptor());
        httpClient.addResponseInterceptor(new GzipProcessResponseInterceptor());
	}

    /**
     * 获取一个URL锁，通过此锁可以防止重复下载
     * @param url 下载地址
     * @return URL锁
     */
	public synchronized ReentrantLock getUrlLock(String url){
		ReentrantLock urlLock = urlLocks.get(url);
		if(urlLock == null){
			urlLock = new ReentrantLock();
			urlLocks.put(url, urlLock);
		}
		return urlLock;
	}

	@Override
	public synchronized boolean isDownloadingByCacheFilePath(String cacheFilePath) {
		return downloadingFiles.contains(cacheFilePath);
	}

	@Override
	public Object download(DownloadRequest request) {
        // 根据下载地址加锁，防止重复下载
        ReentrantLock urlLock = getUrlLock(request.getUri());
        urlLock.lock();

        Object result = null;
        int number = 0;
        while(true){
            try {
                result = realDownload(request);
                break;
            } catch (Throwable e) {
                if(e instanceof CanceledException){
                    break;
                }else{
                    boolean retry = (e instanceof SocketTimeoutException || e instanceof InterruptedIOException) && number < maxRetryCount;
                    if(retry){
                        number++;
                        Log.w(Spear.LOG_TAG, NAME+"；"+"下载异常"+"；"+"再次尝试" + "；" + request.getName());
                    }else{
                        Log.e(Spear.LOG_TAG, NAME+"；"+"下载异常"+"；"+"不再尝试" + "；" + request.getName());
                    }
                    e.printStackTrace();
                    if(!retry){
                        break;
                    }
                }
            }
        }

        // 释放锁
        urlLock.unlock();
        return result;
	}

    private Object realDownload(DownloadRequest request) throws Throwable {
        // 如果已经取消了就直接结束
        if (request.isCanceled()) {
            if (request.getSpear().isDebugMode()) {
                Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - get lock" + "；" + request.getName());
            }
            return null;
        }

        // 如果缓存文件已经存在了就直接返回缓存文件
        File cacheFile = request.getCacheFile();
        if (cacheFile != null && cacheFile.exists()) {
            return cacheFile;
        }

        // 下载
        String lockedFilePath = null;   // 已锁定的文件的路径
        boolean saveToCacheFile = false;  // 是否将数据保存到缓存文件里
        HttpGet httpGet = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;

        try {
            httpGet = new HttpGet(request.getUri());
            HttpResponse httpResponse = httpClient.execute(httpGet);

            if (request.isCanceled()) {
                if (request.getSpear().isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - get response" + "；" + request.getName());
                }
                throw new CanceledException();
            }

            // 检查状态码
            int responseCode = httpResponse.getStatusLine().getStatusCode();
            if (responseCode >= 300) {
                throw new IllegalStateException("状态异常，状态码："+responseCode + " 原因：" + httpResponse.getStatusLine().getReasonPhrase());
            }

            // 检查内容长度
            long contentLength = 0;
            Header[] headers = httpResponse.getHeaders("Content-Length");
            if(headers != null && headers.length > 0){
                contentLength = Long.valueOf(headers[0].getValue());
            }
            if (contentLength <= 0) {
                throw new IOException("Content-Length 为 0");
            }

            // 根据需求创建缓存文件并标记为正在下载
            saveToCacheFile = cacheFile != null && request.getSpear().getDiskCache().applyForSpace(contentLength) && createFile(cacheFile);
            if (request.isCanceled()) {
                if (request.getSpear().isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - create cache file" + "；" + request.getName());
                }
                throw new CanceledException();
            }

            // 锁定文件，标记为正在下载
            if (saveToCacheFile) {
                downloadingFiles.add(lockedFilePath = cacheFile.getPath());
            }

            // 获取输入流后判断是否已取消
            inputStream = httpResponse.getEntity().getContent();
            if (request.isCanceled()) {
                if (request.getSpear().isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - get input stream" + "；" + request.getName());
                }
                throw new CanceledException();
            }

            // 根据是否需要缓存到本地创建不同的输出流
            ByteArrayOutputStream byteArrayOutputStream = null;
            outputStream = new BufferedOutputStream(saveToCacheFile ? new FileOutputStream(cacheFile, false) : (byteArrayOutputStream = new ByteArrayOutputStream()), 8 * 1024);

            // 读取数据
            long completedLength = copy(inputStream, outputStream, request, contentLength);
            if (request.isCanceled()) {
                if (request.getSpear().isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - read data end" + "；" + request.getName());
                }
                throw new CanceledException();
            }

            // 转换结果
            Object result = saveToCacheFile ? cacheFile : byteArrayOutputStream.toByteArray();
            if (request.getSpear().isDebugMode()) {
                Log.i(Spear.LOG_TAG, NAME + "：" + "下载成功" + "；" + "文件长度：" + completedLength + "/" + contentLength + "；" + request.getName());
            }

            // 各种关闭
            try { outputStream.flush(); } catch (IOException e) { e.printStackTrace(); }
            try { outputStream.close(); } catch (IOException e) { e.printStackTrace(); }
            try { inputStream.close(); } catch (IOException e) { e.printStackTrace(); }

            // 解除文件锁定
            if (lockedFilePath != null) {
                downloadingFiles.remove(lockedFilePath);
            }

            return result;
        } catch (Throwable throwable) {
            // 各种关闭
            if (outputStream != null) {
                try { outputStream.flush(); } catch (IOException e) { e.printStackTrace(); }
                try { outputStream.close(); } catch (IOException e) { e.printStackTrace(); }
            }
            if (inputStream != null) {
                try { inputStream.close(); } catch (IOException e) { e.printStackTrace(); }
            }
            if(httpGet != null){
                httpGet.abort();
            }

            // 如果发生异常并且使用了缓存文件以及缓存文件存在就删除缓存文件，然后如果删除失败就输出LOG
            if(saveToCacheFile && cacheFile != null && cacheFile.exists() && !cacheFile.delete()) {
                Log.e(Spear.LOG_TAG, NAME + "：" + "删除缓存文件失败：" + cacheFile.getPath());
            }

            // 解除文件锁定
            if (lockedFilePath != null) {
                downloadingFiles.remove(lockedFilePath);
            }
            throw throwable;
        }
    }

    public long copy(InputStream inputStream, OutputStream outputStream, DownloadRequest downloadRequest, long contentLength) throws IOException {
        int readNumber;	//读取到的字节的数量
        long completedLength = 0;
        long averageLength = contentLength/10;
        int callbackNumber = 0;
        byte[] cacheBytes = new byte[1024*4];//数据缓存区
        while(!downloadRequest.isCanceled() && (readNumber = inputStream.read(cacheBytes)) != -1){
            outputStream.write(cacheBytes, 0, readNumber);
            completedLength += readNumber;
            if(downloadRequest.getDownloadProgressCallback() != null && (completedLength >= (callbackNumber+1)*averageLength || completedLength == contentLength)){
                callbackNumber++;
                downloadRequest.getDownloadProgressCallback().onUpdateProgress(contentLength, completedLength);
            }
        }
        outputStream.flush();
        return completedLength;
    }

    /**
     * 创建文件，一定要保证给定的文件是存在的，创建的关键是如果文件所在的目录不存在的话就先创建目录
     * @param file 要创建的文件
     * @return true：创建好了
     */
    public static boolean createFile(File file){
        if(!file.exists()){
            File parentDir = file.getParentFile();
            if(!parentDir.exists()){
                parentDir.mkdirs();
            }
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return file.exists();
    }

    private static class GzipProcessRequestInterceptor implements HttpRequestInterceptor {
        /**
         * 头字段 - 接受的编码
         */
        public static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";

        /**
         * 编码 - gzip
         */
        public static final String ENCODING_GZIP = "gzip";

        @Override
        public void process(HttpRequest request, HttpContext context) throws HttpException, IOException {
            //如果请求头中没有HEADER_ACCEPT_ENCODING属性就添加进去
            if (!request.containsHeader(HEADER_ACCEPT_ENCODING)) {
                request.addHeader(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
            }
        }
    }

    private static class GzipProcessResponseInterceptor implements HttpResponseInterceptor {

        @Override
        public void process(HttpResponse response, HttpContext context) {
            final HttpEntity entity = response.getEntity();
            if(entity != null) {
                final Header encoding = entity.getContentEncoding();
                if (encoding != null) {
                    for (HeaderElement element : encoding.getElements()) {
                        if (element.getName().equalsIgnoreCase(GzipProcessRequestInterceptor.ENCODING_GZIP)) {
                            response.setEntity(new InflatingEntity(entity));
                            break;
                        }
                    }
                }
            }
        }

        private static class InflatingEntity extends HttpEntityWrapper {
            public InflatingEntity(HttpEntity wrapped) {
                super(wrapped);
            }

            @Override
            public InputStream getContent() throws IOException {
                return new GZIPInputStream(wrappedEntity.getContent());
            }

            @Override
            public long getContentLength() {
                return -1;
            }
        }
    }

    private static class CanceledException extends IOException{
    }
}