package org.http.channel.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.http.channel.proxy.ProxySession;
import org.http.channel.proxy.RemoteStatus;
import org.http.channel.settings.Settings;

public class ProxyClient {
	private Log log = LogFactory.getLog("gate");
	public static final String XAUTH = "X-proxy-auth";
		
	//public String status = DISCONNECTED;
	public RemoteStatus status = new RemoteStatus();
	public Settings settings = null;
	public AuthManager auth = null;
	public boolean isRunning = false;
	private Collection<StatusListener> statusListeners = new ArrayList<StatusListener>(); 
	private ThreadPoolExecutor proxyWorkerPool = null;
	private ThreadPoolExecutor proxyCommandPool = null;
	private URL remote = null;
	private String local = null;
	private Timer timer = new Timer();
	private long commandCount = 0;
	
	private StatusListener listenerProxy = new StatusListener(){
		@Override
		public void updated(RemoteStatus r) {
			for(StatusListener l: statusListeners){
				l.updated(r);
			}
		}};
	
	public ProxyClient(Settings s){
		this.settings = s;
		
		//this.remote = new URL(s.getString(REMOTE, ""));
		//this.local = s.getString(LOCAL, "");
	}
	
	public void run(){
		int core_thread_count = 5;
		
		auth = new AuthManager(this.settings);
		auth.load();
		
		proxyWorkerPool = new ThreadPoolExecutor(
				core_thread_count,
				settings.getInt(Settings.MAX_ROUTE_THREAD_COUNT, 500),
				60, 
				TimeUnit.SECONDS, 
				new LinkedBlockingDeque<Runnable>(core_thread_count * 2)
				);

		proxyCommandPool = new ThreadPoolExecutor(
				2, 5, 60, TimeUnit.SECONDS, 
				new LinkedBlockingDeque<Runnable>(50)
				);
		proxyCommandPool.execute(new RequestTracker());		
		log.info(String.format("Start proxy client, remote:%s, local:%s", remote, local));
		
		timer.scheduleAtFixedRate(new TrackerScheduler(), 100, 60 * 1000);
		isRunning = true;
	}
	
	public void connect(){
		proxyCommandPool.execute(new RequestTracker());	
	}
	
	public void addStatusListener(StatusListener l){
		this.statusListeners.add(l);
	}
	public void removeStatusListener(StatusListener l){
		this.statusListeners.remove(l);
	}
	
	/**
	 * 判断需要多少个下载命令的线程。
	 * @author deon
	 */
	class TrackerScheduler extends TimerTask {
		@Override
		public void run() {
			if(proxyCommandPool.getActiveCount() < 2) {
				proxyCommandPool.execute(new RequestTracker());
			}else if(commandCount > 10 && proxyCommandPool.getActiveCount() < 5){
				proxyCommandPool.execute(new RequestTracker());
			}
			log.info(String.format("Active thread:%s, executed proxy command count:%s", 
					proxyCommandPool.getActiveCount(),
					commandCount));
			commandCount = 0;
		}
	}
	
	/**
	 * 用来从远程服务器，下载需要请求的HTTP命令。
	 * @author deon
	 */
	class RequestTracker implements Runnable{
		@Override
		public void run() {
			ObjectInputStream ios = null;
			HttpURLConnection connection = null;
			String remoteURL = settings.getString(Settings.REMOTE_DOMAIN, "");
			try {				
				remote = new URL(remoteURL);				
				URL request = new URL(remote + "/~/request");
				log.debug("connecting to " + request.toString());
				connection = (HttpURLConnection )request.openConnection();
				connection.setReadTimeout(1000 * 60 * 5);
				connection.setConnectTimeout(1000 * 30);
				
				if(settings.getString("client_secret_key", null) != null){
					connection.addRequestProperty(XAUTH, settings.getString(Settings.PROXY_SECRET_KEY, null));
				}
				connection.setRequestMethod("POST");
				connection.setDoInput(true);
				connection.connect();
				
				ios = new ObjectInputStream(connection.getInputStream());
				for(Object obj = ios.readObject(); obj != null; obj = ios.readObject()){
					commandCount++;
					if(obj instanceof ProxySession){
						status.requestCount++;
						//log.debug("Request:" + obj.toString());
						ProxySession session = (ProxySession)obj;
						if(session.queryURL .startsWith("/~") ||
							session.queryURL .startsWith("~")){
							proxyWorkerPool.execute(new CommandWorker(session, remote));
						}else {
							proxyWorkerPool.execute(new TaskWorker(session, remote));
						}
					}else if(obj instanceof RemoteStatus){
						log.debug("status:" + obj.toString());
						status.copyFrom((RemoteStatus)obj);
						status.updated();
						listenerProxy.updated(status);
					}
				}				
			}catch(ConnectException conn){
				log.info(String.format("Failed connection to '%s', msg:%s", remote, conn.toString()));
				synchronized(status){
					status.connection = RemoteStatus.DISCONNECTED;
					status.updated();
					listenerProxy.updated(status);
				}
			}catch(MalformedURLException e){
				log.info(String.format("Invalid remote url:" + remoteURL));				
			}catch(IOException eof){
				log.info(String.format("EOF read proxy command. msg:%s", eof.toString()));
			}catch (Exception e) {
				log.error(e.toString(), e);
			} finally {
				if (ios != null)
					try {
						ios.close();
					} catch (IOException e) {
						log.error(e.toString(), e);
					}
				if (connection != null) connection.disconnect();
				//threadPool.execute(new RequestTracker());
			}
		}
	}
	

	
	class CommandWorker extends AbstractWorker implements Runnable {		
		public CommandWorker(ProxySession s, URL remote) {
			super(s, remote);
		}

		public void run(){
	    	String[] tmps = request.queryURL.split("/");
	    	String cmd = tmps[tmps.length - 1].toLowerCase().trim();
	    	try{
		    	if(cmd.equals("user_login") && auth != null){
		    		String username = request.header.get("username");
		    		String password = request.header.get("password");
		    		if(auth.login(request, username, password)){
		    			log.info("login successful, user:" + username);
		    			forwardAuthOK(request);
		    		}else {
		    			forwardAuthRequest(request);
		    		}
		    	}
	    	}catch (Exception e) {
				log.error(e.toString(), e);
			}
		}
	}

	/**
	 * 用来访问本地的最终服务，并把结果转发回代理服务器。
	 * @author deon
	 */
	class TaskWorker extends AbstractWorker implements Runnable{
		public TaskWorker(ProxySession s, URL remote) {
			super(s, remote);
		}

		@Override
		public void run() {
			HttpURLConnection connection = null;
			InputStream ins = null;
			try {
				if(auth != null && !auth.hasPermession(request)){
					log.info(String.format("Access denied:%s, session:%s", request.queryURL, request.sid));
					forwardAuthRequest(request);
					return;
				}
				
				log.info("Start forward: " + request.toString());
				local = settings.getString(Settings.INTERNAL_DOMAIN, "");
				
				URL localURL = new URL(local + request.queryURL);
				connection = (HttpURLConnection) localURL.openConnection(Proxy.NO_PROXY);
				connection.setRequestMethod(request.method);
				
				
				/**
				 * Set default header.
				 */
				connection.setRequestProperty("Content-type", "text/html");
				connection.setRequestProperty("Cache-Control", "no-cache");
				
				
				for(String key: request.header.keySet()){
					//log.info(key + "-->" + request.header.get(key));
					if(key.equals("Host")){
						continue;
					}
					connection.setRequestProperty(key, request.header.get(key));
				}
				
				connection.setDoInput(true);
				if(this.request.content != null){
					OutputStream os = null;
					connection.setDoOutput(true);
					os = connection.getOutputStream();
					os.write(this.request.content);
					os.close();
				}
				connection.connect();
				ins = connection.getInputStream();
				
				log.debug(String.format("Local Done [%s] '%s' length:%s, type:%s",
						connection.getResponseCode(),
						request.queryURL,
						connection.getContentLength(),
						connection.getContentType()
						));
				if(connection.getContentType() != null && 
					connection.getContentType().toLowerCase().contains("text")){
					//connection.getHeaderFields()
					String[] _tmp = connection.getContentType().split("=");
					String charset = "utf8";
					if(_tmp.length == 2){
						charset = _tmp[1];
					}
					String encoding = connection.getContentEncoding();
					this.convertContentLink(ins, localURL, remote, connection.getHeaderFields(), connection.getResponseCode(), encoding, charset);
				}else {
					this.uploadResponse(ins, connection.getHeaderFields(), connection.getResponseCode());
				}
				listenerProxy.updated(status);
			}catch(ConnectException conn){
				log.info(String.format("Failed connection to '%s', msg:%s", local, conn.toString()));
			} catch (Exception e) {
				log.error(e.toString(), e);
			} finally{
				if(ins != null){
					try {
						ins.close();
					} catch (IOException e) {
						log.error(e, e);
					}
				}
			}
		}
				
		private void convertContentLink(InputStream in, URL local, URL remote, Map<String, List<String>> header, 
				int code, String encode, String charset) throws IOException{
			HTTPForm form = this.createUploadResponse(header, code);
			
			if(in != null){
				form.startFileStream("file0", "file", null);
			}
			
			InputStream newIns = in;
			OutputStream os = form.out;
			if(encode != null && encode.equals("gzip")){
				newIns = new GZIPInputStream(in);
				os = new GZIPOutputStream(form.out);
			}
			
		    byte[] buffer = new byte[64 * 1024];
		    String localURL = null, remoteURL = null;
		    try {
		    	localURL = local.toURI().resolve("/").toString();
		    	remoteURL = remote.toURI().resolve("/").toString();
			} catch (URISyntaxException e) {
				log.error(e.toString(), e);
			}
		    
		    int count = 0;
		    int offset = 0;
		    for(int len = 0; len >= 0; ){
		    	len = newIns.read(buffer, offset, buffer.length - offset);
		    	if(len > 0){
		    		offset += len;
		    		if(offset >= buffer.length){
		    			count += offset;
		    			String newData = new String(buffer, 0, offset, charset).replaceAll(localURL, remoteURL);
		    			os.write(newData.getBytes(charset));
		    			offset = 0;
		    		}
		    	}
		    }

    		if(offset > 0){
    			count += offset;
    			String newData = new String(buffer, 0, offset, charset).replaceAll(localURL, remoteURL);
    			os.write(newData.getBytes(charset));
    			offset = 0;
    		}
    		
    		os.flush();    		
			String data = form.read();
			form.close();
			log.debug(String.format("Proxy done:%s, len:%s, msg:%s", this.request.queryURL, count, data));			
		}
		
	}
}
