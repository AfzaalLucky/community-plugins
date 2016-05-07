/**
 * $Revision $
 * $Date $
 *
 * Copyright (C) 2005-2010 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.plugin.ofmeet;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import java.text.*;
import java.util.regex.*;
import javax.servlet.DispatcherType;

import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;
import org.xmpp.packet.*;

import org.jivesoftware.util.*;
import org.jivesoftware.openfire.plugin.spark.*;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.session.LocalClientSession;
import org.jivesoftware.openfire.cluster.ClusterEventListener;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.auth.AuthToken;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.openfire.muc.*;
import org.jivesoftware.openfire.group.*;
import org.jivesoftware.openfire.session.*;
import org.jivesoftware.openfire.event.*;
import org.jivesoftware.openfire.security.SecurityAuditManager;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.roster.RosterManager;

import org.xmpp.component.ComponentManager;
import org.xmpp.component.ComponentManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.webapp.WebAppContext;

import org.eclipse.jetty.util.security.*;
import org.eclipse.jetty.security.*;
import org.eclipse.jetty.security.authentication.*;

import org.ifsoft.websockets.*;

import org.jitsi.videobridge.openfire.PluginImpl;
import org.jitsi.jigasi.openfire.CallControlComponent;
import org.jitsi.jigasi.openfire.JigasiPlugin;
import org.jitsi.jicofo.openfire.JicofoPlugin;

import net.sf.json.*;
import org.dom4j.*;

public class OfMeetPlugin implements Plugin, SessionEventListener, ClusterEventListener  {

    private static final Logger Log = LoggerFactory.getLogger(OfMeetPlugin.class);
    private final ConcurrentHashMap<String, XMPPServlet.XMPPWebSocket> sockets = new ConcurrentHashMap<String, XMPPServlet.XMPPWebSocket>();
	private PluginImpl jitsiPlugin;
	private JigasiPlugin jigasiPlugin;
	private JicofoPlugin jicofoPlugin;
	private PluginManager manager;
	public File pluginDirectory;
    private TaskEngine taskEngine = TaskEngine.getInstance();
    private UserManager userManager = XMPPServer.getInstance().getUserManager();
    private ComponentManager componentManager;
    private OfMeetIQHandler ofmeetIQHandler = null;

    public static OfMeetPlugin self;
	public static String ofmeetHome = JiveGlobals.getHomeDirectory() + File.separator + "resources" + File.separator + "spank" + File.separator + "ofmeet-cdn";

	public String sipRegisterStatus = "";

    public String getName() {
        return "ofmeet";
    }

    public String getDescription() {
        return "OfMeet Plugin";
    }

	public PluginImpl getPlugin()
	{
		return jitsiPlugin;
	}

    public void initializePlugin(final PluginManager manager, final File pluginDirectory)
    {
        componentManager = ComponentManagerFactory.getComponentManager();
		ContextHandlerCollection contexts = HttpBindManager.getInstance().getContexts();

		this.manager = manager;
		this.pluginDirectory = pluginDirectory;
		self = this;

		try {

			try {
				Log.info("OfMeet Plugin - Initialize jitsi videobridge ");

				jitsiPlugin = new PluginImpl();
				jitsiPlugin.initializePlugin(componentManager, manager, pluginDirectory);
			}
			catch (Exception e1) {
				Log.error("Could NOT Initialize jitsi videobridge", e1);
			}

			// Determine the JID of the 'focus' user.
			final String defaultValue = "focus@" + XMPPServer.getInstance().getServerInfo().getXMPPDomain();
			final String propertyValue = JiveGlobals.getProperty( "org.jitsi.videobridge.ofmeet.focus.user.jid", defaultValue );
			JID focusUserJid;
			try {
				focusUserJid = new JID( propertyValue );
			} catch (IllegalArgumentException e) {
				Log.warn( "The 'org.jitsi.videobridge.ofmeet.focus.user.jid' property contains a value ('{}') that appears to be in invalid JID.", propertyValue, e );
				focusUserJid = new JID( defaultValue );
			}

			// Ensure that the 'focus' user exists if it is supposed to be a user of our domain.
			if ( focusUserJid.getDomain().equalsIgnoreCase( XMPPServer.getInstance().getServerInfo().getXMPPDomain() ) )
			{
				try {
					userManager.getUser( focusUserJid.getNode() );
				}
				catch (UserNotFoundException e) {

					Log.info("OfMeet Plugin - Setup focus user " + focusUserJid);

					String focusUserPassword = JiveGlobals.getProperty( "org.jitsi.videobridge.ofmeet.focus.user.password", "focus-password-" + StringUtils.randomString( 15 ) );

					try {
						userManager.createUser( focusUserJid.getNode(), focusUserPassword, "Openfire Meetings Focus User", focusUserJid.toString() );

						JiveGlobals.setProperty("org.jitsi.videobridge.ofmeet.focus.user.jid", focusUserJid.toString() );
						JiveGlobals.setProperty("org.jitsi.videobridge.ofmeet.focus.user.password", focusUserPassword);
					}
					catch (Exception e1) {

						Log.error("Could NOT create focus user", e1);
					}
				}
			}

			// Ensure that the 'focus' user is a sysadmin of the conference service(s).
			for ( MultiUserChatService mucService : XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices() )
			{
				if (!mucService.isSysadmin( focusUserJid ))
				{
					mucService.addSysadmin( focusUserJid );
				}
			}

			new Timer().schedule( new TimerTask()
			{
				@Override
				public void run()
				{
					try
					{
						Log.info( "OfMeet Plugin - Initialize jitsi conference focus" );

						jicofoPlugin = new JicofoPlugin();
						jicofoPlugin.initializePlugin( componentManager, manager, pluginDirectory );
					}
					catch ( Exception e1 )
					{
						Log.error( "Could NOT Initialize jicofo component", e1 );
					}

					try
					{
						Log.info( "OfMeet Plugin - Initialize call control component " );

						jigasiPlugin = new JigasiPlugin();
						jigasiPlugin.initializePlugin( componentManager, manager, pluginDirectory );
					}
					catch ( Exception e1 )
					{
						Log.error( "Could NOT Initialize jigasi component", e1);
					}
				}
			}, 5000);

			try {

				boolean bookmarks = XMPPServer.getInstance().getPluginManager().getPlugin("bookmarks") != null;

				if (bookmarks)
				{
					new Timer().scheduleAtFixedRate(new TimerTask()
					{
						@Override public void run()
						{
							processMeetingPlanner();
						}

					}, 0,  900000);
				}

			} catch (Exception e) {

				Log.error("Meeting Planner Executor error", e);
			}

			ClusterManager.addListener(this);

			Log.info("OfMeet Plugin - Initialize websockets ");

			ServletContextHandler context = new ServletContextHandler(contexts, "/ofmeetws", ServletContextHandler.SESSIONS);
			context.addServlet(new ServletHolder(new XMPPServlet()),"/server");

			// Ensure the JSP engine is initialized correctly (in order to be able to cope with Tomcat/Jasper precompiled JSPs).

			final List<ContainerInitializer> initializers = new ArrayList<>();
			initializers.add(new ContainerInitializer(new JettyJasperInitializer(), null));
			context.setAttribute("org.eclipse.jetty.containerInitializers", initializers);
			context.setAttribute(InstanceManager.class.getName(), new SimpleInstanceManager());

			Log.info("OfMeet Plugin - Initialize webservice");

			WebAppContext context2 = new WebAppContext(contexts, pluginDirectory.getPath(), "/ofmeet");
			context2.setClassLoader(this.getClass().getClassLoader());

			// Ensure the JSP engine is initialized correctly (in order to be able to cope with Tomcat/Jasper precompiled JSPs).

			final List<ContainerInitializer> initializers2 = new ArrayList<>();
			initializers2.add(new ContainerInitializer(new JettyJasperInitializer(), null));
			context2.setAttribute("org.eclipse.jetty.containerInitializers", initializers2);
			context2.setAttribute(InstanceManager.class.getName(), new SimpleInstanceManager());

			context2.setWelcomeFiles(new String[]{"index.html"});

			String securityEnabled = JiveGlobals.getProperty("ofmeet.security.enabled", "true");

			if ("true".equals(securityEnabled))
			{
				Log.info("OfMeet Plugin - Initialize security");

				if (JiveGlobals.getProperty("org.jitsi.videobridge.ofmeet.windows.sso", "off").equals("on"))
				{
					waffle.servlet.NegotiateSecurityFilter securityFilter = new waffle.servlet.NegotiateSecurityFilter();
					FilterHolder filterHolder = new FilterHolder();
					filterHolder.setFilter(securityFilter);
					EnumSet<DispatcherType> enums = EnumSet.of(DispatcherType.REQUEST);
					enums.add(DispatcherType.REQUEST);
					context2.addFilter(filterHolder, "/*", enums);
				}
				else {
					context2.setSecurityHandler(basicAuth("ofmeet"));
				}
			}

			Log.info("OfMeet Plugin - Initialize email listener");

			checkDownloadFolder(pluginDirectory);
        	EmailListener.getInstance().start();

			Log.info("OfMeet Plugin - Initialize IQ handler ");

			ofmeetIQHandler = new OfMeetIQHandler();
			XMPPServer.getInstance().getIQRouter().addHandler(ofmeetIQHandler);

        	SessionEventDispatcher.addListener(this);

		} catch (Exception e) {
			Log.error("Could NOT start open fire meetings", e);
		}
    }

    public void destroyPlugin() {
        try {

        	SessionEventDispatcher.removeListener(this);

			XMPPServer.getInstance().getIQRouter().removeHandler(ofmeetIQHandler);
			ofmeetIQHandler = null;

			for (XMPPServlet.XMPPWebSocket socket : sockets.values())
			{
				try {
					LocalClientSession session = socket.getSession();
					session.close();
					SessionManager.getInstance().removeSession( session );
					session = null;

				} catch ( Exception e ) { }
			}

			sockets.clear();

			jitsiPlugin.destroyPlugin();
			jigasiPlugin.destroyPlugin();
			jicofoPlugin.destroyPlugin();

        	ClusterManager.removeListener(this);

			EmailListener.getInstance().stop();

        } catch (Exception e) {

        }
    }

    private static final SecurityHandler basicAuth(String realm) {

    	OpenfireLoginService l = new OpenfireLoginService();
        l.setName(realm);

        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__BASIC_AUTH);
        constraint.setRoles(new String[]{"ofmeet"});
        constraint.setAuthenticate(true);

        ConstraintMapping cm = new ConstraintMapping();
        cm.setConstraint(constraint);
        cm.setPathSpec("/*");

        ConstraintSecurityHandler csh = new ConstraintSecurityHandler();
        csh.setAuthenticator(new BasicAuthenticator());
        csh.setRealmName(realm);
        csh.addConstraintMapping(cm);
        csh.setLoginService(l);

        return csh;
    }

	public ConcurrentHashMap<String, XMPPServlet.XMPPWebSocket> getSockets()
	{
		return sockets;
	}

    private void checkDownloadFolder(File pluginDirectory)
    {
        try
        {
			File ofmeetFolderPath = new File(ofmeetHome);

            if(!ofmeetFolderPath.exists())
            {
                ofmeetFolderPath.mkdirs();

			}

			List<String> lines = Arrays.asList("Move on, nothing here....");
			Path file = Paths.get(ofmeetHome + File.separator + "index.html");
			Files.write(file, lines, Charset.forName("UTF-8"));

			File downloadHome = new File(ofmeetHome + File.separator + "download");

            if(!downloadHome.exists())
            {
                downloadHome.mkdirs();
			}

			File recordingsHome = new File(ofmeetHome + File.separator + "recordings");

            if(!recordingsHome.exists())
            {
                recordingsHome.mkdirs();
			}

			lines = Arrays.asList("Move on, nothing here....");

			file = Paths.get(downloadHome + File.separator + "index.html");
			Files.write(file, lines, Charset.forName("UTF-8"));

			file = Paths.get(recordingsHome + File.separator + "index.html");
			Files.write(file, lines, Charset.forName("UTF-8"));
        }
        catch (Exception e)
        {
            Log.error("checkDownloadFolder", e);
        }
	}

	//-------------------------------------------------------
	//
	//		clustering
	//
	//-------------------------------------------------------

	@Override
	public void joinedCluster()
	{
		Log.info("OfMeet Plugin - joinedCluster");
		jitsiPlugin.destroyPlugin();
		jigasiPlugin.destroyPlugin();
		jicofoPlugin.destroyPlugin();
	}

	@Override
	public void joinedCluster(byte[] arg0)
	{


	}

	@Override
	public void leftCluster()
	{
		Log.info("OfMeet Plugin - leftCluster");
		jitsiPlugin.initializePlugin(componentManager, manager, pluginDirectory);
		jigasiPlugin.initializePlugin(componentManager, manager, pluginDirectory);
		jicofoPlugin.initializePlugin(componentManager, manager, pluginDirectory);
	}

	@Override
	public void leftCluster(byte[] arg0)
	{


	}

	@Override
	public void markedAsSeniorClusterMember()
	{
		Log.info("OfMeet Plugin - markedAsSeniorClusterMember");

		jitsiPlugin.initializePlugin(componentManager, manager, pluginDirectory);
		jigasiPlugin.initializePlugin(componentManager, manager, pluginDirectory);
		jicofoPlugin.initializePlugin(componentManager, manager, pluginDirectory);
	}

	//-------------------------------------------------------
	//
	//		meeting planner
	//
	//-------------------------------------------------------

	public void processMeetingPlanner()
	{
		Log.debug("OfMeet Plugin - processMeetingPlanner");

		final Collection<Bookmark> bookmarks = BookmarkManager.getBookmarks();

		String hostname = XMPPServer.getInstance().getServerInfo().getHostname();

		for (Bookmark bookmark : bookmarks)
		{
			if (bookmark.getType() == Bookmark.Type.group_chat)
			{
				String url = bookmark.getProperty("url");

				if (url == null)
				{
					String id = bookmark.getBookmarkID() + "" + System.currentTimeMillis();
					String rootUrl = JiveGlobals.getProperty("ofmeet.root.url.secure", "https://" + hostname + ":" + JiveGlobals.getProperty("httpbind.port.secure", "7443"));
					url = rootUrl + "/ofmeet/?b=" + id;
					bookmark.setProperty("url", url);
				}
			}

			String json = bookmark.getProperty("calendar");

			if (json != null)
			{
				bookmark.setProperty("lock", "true");

				JSONArray calendar = new JSONArray(json);
				boolean done = false;

				for(int i = 0; i < calendar.length(); i++)
				{
					try {
						JSONObject meeting = calendar.getJSONObject(i);

						boolean processed = meeting.getBoolean("processed");
						long startLong = meeting.getLong("startTime");

						Date rightNow = new Date(System.currentTimeMillis());
						Date actionDate = new Date(startLong + 300000);
						Date warnDate = new Date(startLong - 960000);

						Log.debug("OfMeet Plugin - scanning meeting now " + rightNow + " action " + actionDate + " warn " + warnDate + "\n" + meeting );

						if(rightNow.after(warnDate) && rightNow.before(actionDate))
						{
							for (String user : bookmark.getUsers())
							{
								processMeeting(meeting, user, bookmark.getProperty("url"));
							}

							for (String groupName : bookmark.getGroups())
							{
								try {
									Group group = GroupManager.getInstance().getGroup(groupName);

									for (JID memberJID : group.getMembers())
									{
										processMeeting(meeting, memberJID.getNode(), bookmark.getProperty("url"));
									}

								} catch (GroupNotFoundException e) { }
							}

							meeting.put("processed", true);
							done = true;
						}
					} catch (Exception e) {
						Log.error("processMeetingPlanner", e);
					}
				}

				if (done)
				{
					json = calendar.toString();
					bookmark.setProperty("calendar", json);

					Log.debug("OfMeet Plugin - processed meeting\n" + json);
				}

				bookmark.setProperty("lock", "false");
			}
		}
	}

	public void processMeeting(JSONObject meeting, String username, String videourl)
	{
		Log.info("OfMeet Plugin - processMeeting " + username + " " + meeting);

	   	try {
			User user = userManager.getUser(username);
			Date start = new Date(meeting.getLong("startTime"));
			Date end = new Date(meeting.getLong("endTime"));
			String name = user.getName();
			String email = user.getEmail();
			String description = meeting.getString("description");
			String title = meeting.getString("title");
			String room = meeting.getString("room");
			//String videourl = "https://" + XMPPServer.getInstance().getServerInfo().getHostname() + ":" + JiveGlobals.getProperty("httpbind.port.secure", "7443") + "/ofmeet/?r=" + room;
			String audiourl = videourl + "&novideo=true";
			String template = JiveGlobals.getProperty("ofmeet.email.template", "Dear [name],\n\nYou have an online meeting from [start] to [end]\n\n[description]\n\nTo join, please click\n[videourl]\nFor audio only with no webcan, please click\n[audiourl]\n\nAdministrator - [domain]");

			HashMap variables = new HashMap<String, String>();

			if (email != null)
			{
				variables.put("name", name);
				variables.put("email", email);
				variables.put("start", start.toString());
				variables.put("end", end.toString());
				variables.put("description", description);
				variables.put("title", title);
				variables.put("room", room);
				variables.put("videourl", videourl);
				variables.put("audiourl", audiourl);
				variables.put("domain", XMPPServer.getInstance().getServerInfo().getXMPPDomain());

				sendEmail(name, email, title, replaceTokens(template, variables), null);
				SecurityAuditManager.getInstance().logEvent(user.getUsername(), "sent email - " + title, description);
			}
	   }
	   catch (Exception e) {
		   Log.error("processMeeting error", e);
	   }
	}

	public void sendEmail(String toName, String toAddress, String subject, String body, String htmlBody)
	{
	   try {
		   String fromAddress = "no_reply@" + JiveGlobals.getProperty("ofmeet.email.domain", XMPPServer.getInstance().getServerInfo().getXMPPDomain());
		   String fromName = JiveGlobals.getProperty("ofmeet.email.fromname", "Openfire Meetings");

		   Log.debug( "sendEmail " + toAddress + " " + subject + "\n " + body + "\n " + htmlBody);
		   EmailService.getInstance().sendMessage(toName, toAddress, fromName, fromAddress, subject, body, htmlBody);
	   }
	   catch (Exception e) {
		   Log.error(e.toString());
	   }

	}

	public String replaceTokens(String text, Map<String, String> replacements)
	{
		Pattern pattern = Pattern.compile("\\[(.+?)\\]");
		Matcher matcher = pattern.matcher(text);
		StringBuffer buffer = new StringBuffer();

		while (matcher.find())
		{
			String replacement = replacements.get(matcher.group(1));

			if (replacement != null)
			{
				matcher.appendReplacement(buffer, "");
				buffer.append(replacement);
			}
		}
		matcher.appendTail(buffer);
		return buffer.toString();
	}

	//-------------------------------------------------------
	//
	//		session management
	//
	//-------------------------------------------------------

	public void anonymousSessionCreated(Session session)
	{
		Log.debug("OfMeet Plugin -  anonymousSessionCreated "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
	}

	public void anonymousSessionDestroyed(Session session)
	{
		Log.debug("OfMeet Plugin -  anonymousSessionDestroyed "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
	}

	public void resourceBound(Session session)
	{
		Log.debug("OfMeet Plugin -  resourceBound "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
	}

	public void sessionCreated(Session session)
	{
		Log.debug("OfMeet Plugin -  sessionCreated "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
	}

	public void sessionDestroyed(Session session)
	{
		Log.debug("OfMeet Plugin -  sessionDestroyed "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
	}

	//-------------------------------------------------------
	//
	//		custom IQ handler for user and group properties JSON request/response
	//
	//-------------------------------------------------------

    public class OfMeetIQHandler extends IQHandler
    {
        public OfMeetIQHandler()
        {
			super("Openfire Meetings IQ Handler");
		}

        @Override public IQ handleIQ(IQ iq)
        {
			IQ reply = IQ.createResultIQ(iq);

			try {
				Log.info("Openfire Meetings handleIQ \n" + iq.toString());
				final Element element = iq.getChildElement();
				JID from = iq.getFrom();

				JSONObject requestJSON = new JSONObject(element.getText());
				String action = requestJSON.getString("action");

				if ("get_user_properties".equals(action)) getUserProperties(iq.getFrom().getNode(), reply, requestJSON);
				if ("set_user_properties".equals(action)) setUserProperties(iq.getFrom().getNode(), reply, requestJSON);
				if ("get_user_groups".equals(action)) getUserGroups(iq.getFrom().getNode(), reply, requestJSON);
				if ("get_conference_id".equals(action)) getConferenceId(iq.getFrom().getNode(), reply, requestJSON);

				return reply;

			} catch(Exception e) {
				Log.error("Openfire Meetings handleIQ", e);
				reply.setError(new PacketError(PacketError.Condition.internal_server_error, PacketError.Type.modify, e.toString()));
				return reply;
			}
		}

        @Override public IQHandlerInfo getInfo()
        {
			return new IQHandlerInfo("request", "http://igniterealtime.org/protocol/ofmeet");
		}

		private void setUserProperties(String username, IQ reply, JSONObject requestJSON)
		{
			Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

			try {
				UserManager userManager = XMPPServer.getInstance().getUserManager();
				User user = userManager.getUser(username);

				if (requestJSON != null)
				{
					Iterator<?> keys = requestJSON.keys();

					while( keys.hasNext() )
					{
						String key = (String)keys.next();
						String value = requestJSON.getString(key);

						user.getProperties().put(key, value);
					}
				}

			} catch (Exception e) {
				reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, "User " + username + " " + requestJSON.toString() + " " + e));
				return;
			}
		}

		private void getUserProperties(String defaultUsername, IQ reply, JSONObject requestJSON)
		{
			Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

			try {
				String username = requestJSON.getString("username");

				if (username == null) username = defaultUsername;

				UserManager userManager = XMPPServer.getInstance().getUserManager();
				User user = userManager.getUser(username);

				JSONObject userJSON = new JSONObject();

				userJSON.put("username", JID.unescapeNode(user.getUsername()));
				userJSON.put("name", user.isNameVisible() ? removeNull(user.getName()) : "");
				userJSON.put("email", user.isEmailVisible() ? removeNull(user.getEmail()) : "");

				for(Map.Entry<String, String> props : user.getProperties().entrySet())
				{
					userJSON.put(props.getKey(), props.getValue());
				}

				childElement.setText(userJSON.toString());

			} catch (UserNotFoundException e) {
				reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, "User not found"));
				return;

			} catch (Exception e1) {
				reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, requestJSON.toString() + " " + e1));
				return;
			}
		}

		private void getConferenceId(String defaultUsername, IQ reply, JSONObject requestJSON)
		{
			Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

			try {
				String roomName = requestJSON.getString("room");

				if (CallControlComponent.self.conferences.containsKey(roomName))
				{
					String confId = CallControlComponent.self.conferences.get(roomName);

					JSONObject userJSON = new JSONObject();
					userJSON.put("conference", confId);

					Path dir = Paths.get(JiveGlobals.getProperty("org.jitsi.videobridge.ofmeet.recording.path", ofmeetHome + File.separator + "recordings"));

					try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
					{
						for (Path file: stream)
						{
							String fileName = file.getFileName().toString();

							if (fileName.indexOf(confId) > -1)
							{
								userJSON.put("folder", fileName);
							}
						}

					} catch (IOException | DirectoryIteratorException x) {
						Log.error("getConferenceId", x);
					}

					childElement.setText(userJSON.toString());

				} else {
					reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, "Conference room not found"));
				}

			} catch (Exception e1) {
				reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, requestJSON.toString() + " " + e1));
				return;
			}
		}

		private void getUserGroups(String defaultUsername, IQ reply, JSONObject requestJSON)
		{
			Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

			try {
				String username = requestJSON.getString("username");

				if (username == null) username = defaultUsername;

				UserManager userManager = XMPPServer.getInstance().getUserManager();
				User user = userManager.getUser(username);

				Collection<Group> groups = GroupManager.getInstance().getGroups(user);
				JSONArray groupsJSON = new JSONArray();
				int index = 0;

				for (Group group : groups)
				{
					groupsJSON.put(index++, getJsonFromGroupXml(group.getName()));
				}

				childElement.setText(groupsJSON.toString());

			} catch (UserNotFoundException e) {
				reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, "User not found"));
				return;

			} catch (Exception e1) {
				reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, requestJSON.toString() + " " + e1));
				return;
			}
		}

		private JSONObject getJsonFromGroupXml(String groupname)
		{
			JSONObject groupJSON = new JSONObject();

			try {
				Group group = GroupManager.getInstance().getGroup(groupname);

            	boolean isSharedGroup = RosterManager.isSharedGroup(group);
				Map<String, String> properties = group.getProperties();
            	String showInRoster = (isSharedGroup ? properties.get("sharedRoster.showInRoster") : "");

            	groupJSON.put("name", group.getName());
            	groupJSON.put("desc", group.getDescription());
            	groupJSON.put("count", group.getMembers().size() + group.getAdmins().size());
            	groupJSON.put("shared", String.valueOf(isSharedGroup));
            	groupJSON.put("display", (isSharedGroup ? properties.get("sharedRoster.displayName") : ""));
                groupJSON.put("specified_groups", String.valueOf("onlyGroup".equals(showInRoster) && properties.get("sharedRoster.groupList").trim().length() > 0));
				groupJSON.put("visibility", showInRoster);
				groupJSON.put("groups", (isSharedGroup ? properties.get("sharedRoster.groupList") : ""));

				for(Map.Entry<String, String> props : properties.entrySet())
				{
					groupJSON.put(props.getKey(), props.getValue());
				}

				JSONArray membersJSON = new JSONArray();
				JSONArray adminsJSON = new JSONArray();
				int i = 0;

				for (JID memberJID : group.getMembers())
				{
					JSONObject memberJSON = new JSONObject();
					memberJSON.put("jid", memberJID.toString());
					memberJSON.put("name", memberJID.getNode());
					membersJSON.put(i++, memberJSON);
				}

				groupJSON.put("members", membersJSON);
				i = 0;

				for (JID memberJID : group.getAdmins())
				{
					JSONObject adminJSON = new JSONObject();
					adminJSON.put("jid", memberJID.toString());
					adminJSON.put("name", memberJID.getNode());
					adminsJSON.put(i++, adminJSON);
				}
				groupJSON.put("admins", adminsJSON);

			} catch (Exception e) {
				Log.error("getJsonFromGroupXml", e);
			}

			return groupJSON;
		}

		private String removeNull(String s)
		{
			if (s == null)
			{
				return "";
			}

			return s.trim();
		}
	}

	//-------------------------------------------------------
	//
	//
	//
	//-------------------------------------------------------

}
