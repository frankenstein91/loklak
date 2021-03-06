/**
 *  SearchServlet
 *  Copyright 22.02.2015 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package org.loklak.api.server;

import java.io.IOException;
import java.util.Date;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.loklak.DAO;
import org.loklak.Timeline;
import org.loklak.Tweet;
import org.loklak.User;
import org.loklak.api.RemoteAccess;
import org.loklak.api.ServletHelper;
import org.loklak.rss.RSSFeed;
import org.loklak.rss.RSSMessage;
import org.loklak.tools.CharacterCoding;

/**
 * The search servlet. we provide opensearch/rss and twitter-like JSON as result.
 */
public class SearchServlet extends HttpServlet {

    private static final long serialVersionUID = 563533152152063908L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        // manage DoS
        String clientHost = request.getRemoteHost();
        String XRealIP = request.getHeader("X-Real-IP"); if (XRealIP != null && XRealIP.length() > 0) clientHost = XRealIP; // get IP through nginx config "proxy_set_header X-Real-IP $remote_addr;"
        long now = System.currentTimeMillis();
        long time_since_last_access = now - RemoteAccess.latestVisit(clientHost);
        String path = request.getServletPath();
        RemoteAccess.log(clientHost, path, null, null);
        if (time_since_last_access < DAO.getConfig("DoS.blackout", 1000)) {response.sendError(503, "your request frequency is too high"); return;}
        long servicereduction_time = DAO.getConfig("DoS.servicereduction", 10000);
        boolean grantRemoteSearch = time_since_last_access > servicereduction_time; // pause a bit please

        // check call type
        boolean jsonExt = path.endsWith(".json");
        //boolean xmlExt = path.endsWith(".xml");

        // evaluate get parameter
        Map<String, String> qm = ServletHelper.getQueryMap(request.getQueryString());
        String callback = qm == null ? request.getParameter("callback") : qm.get("callback");
        boolean jsonp = callback != null && callback.length() > 0;
        String minifieds = qm == null ? request.getParameter("minified") : qm.get("minified");
        boolean minified = minifieds != null && "true".equals(minifieds);
        String query = qm == null ? request.getParameter("q") : qm.get("q");
        if (query == null || query.length() == 0) query = qm == null ? request.getParameter("query") : qm.get("query");
        query = CharacterCoding.html2unicode(query).replaceAll("\\+", " ");
        String maximumRecords = qm == null ? request.getParameter("maximumRecords") : qm.get("maximumRecords");
        int count = maximumRecords == null || maximumRecords.length() == 0 ? 100 : Integer.parseInt(maximumRecords);
        String source = qm == null ? request.getParameter("source") : qm.get("source"); // possible values: cache, twitter, all
        if (source == null) source = "all";
        //String collection = qm.get("collection");
        //String order = qm.get("order");
        //String filter = qm.get("filter");
        //String near = qm.get("near");
        //String op = qm.get("op");

        // create tweet timeline
        final Timeline tl = new Timeline();
        if (query.length() > 0) {
            if ("all".equals(source) && grantRemoteSearch) {
                // start all targets for search concurrently
                final String queryf = query;
                Thread scraperThread = new Thread() {
                    public void run() {
                        tl.putAll(DAO.scrapeTwitter(queryf)[0]);
                    }
                };
                scraperThread.start();
                Thread backendThread = new Thread() {
                    public void run() {
                        tl.putAll(DAO.searchBackend(queryf, 100));
                    }
                };
                backendThread.start();
                tl.putAll(DAO.searchLocal(query, count));
                try {backendThread.join(5000);} catch (InterruptedException e) {}
                try {scraperThread.join(8000);} catch (InterruptedException e) {}
            } else {
                if (grantRemoteSearch && "twitter".equals(source)) {
                    tl.putAll(DAO.scrapeTwitter(query)[0]);
                }
    
                // replace the timeline with one from the own index which now includes the remote result
                if (grantRemoteSearch && "backend".equals(source)) {
                    tl.putAll(DAO.searchBackend(query, count));
                }
    
                // replace the timeline with one from the own index which now includes the remote result
                if ("cache".equals(source)) {
                    tl.putAll(DAO.searchLocal(query, count));
                }
            }
        }

        response.setDateHeader("Last-Modified", now);
        response.setDateHeader("Expires", now + servicereduction_time * 2);
        response.setContentType(jsonExt ? (jsonp ? "application/javascript": "application/json") : "application/rss+xml;charset=utf-8");
        response.setHeader("X-Robots-Tag",  "noindex,noarchive,nofollow,nosnippet");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);
        
        // create json or xml according to path extension
        if (jsonExt) {
            // generate json
            XContentBuilder json = XContentFactory.jsonBuilder();
            if (!minified) json = json.prettyPrint();
            json = json.lfAtEnd();
            json.startObject();
            if (!minified) {
                json.field("readme_0", "THIS JSON IS THE RESULT OF YOUR SEARCH QUERY - THERE IS NO WEB PAGE WHICH SHOWS THE RESULT!");
                json.field("readme_1", "loklak.org is the framework for a message search system, not the portal, read: http://loklak.org/about.html#notasearchportal");
                json.field("readme_2", "This is supposed to be the back-end of a search portal. For the api, see http://loklak.org/api.html");
                json.field("readme_3", "Parameters q=(query), source=(cache|twitter|all), callback=p for jsonp, maximumRecords=(message count), minified=(true|false)");
            }
            json.field("search_metadata").startObject();
            json.field("startIndex", "0");
            json.field("itemsPerPage", Integer.toString(count));
            json.field("count", Integer.toString(tl.size()));
            json.field("query", query);
            json.endObject(); // of search_metadata
            json.field("statuses").startArray();
            for (Tweet t: tl) {
                User u = tl.getUser(t);
                t.toJSON(json, u, true);
            }
            json.endArray();
            json.endObject(); // of root

            // write json
            ServletOutputStream sos = response.getOutputStream();
            if (jsonp) sos.print(callback + "(");
            sos.print(json.string());
            if (jsonp) sos.println(");");
            sos.println();
        } else {
            // generate xml
            RSSMessage channel = new RSSMessage();
            channel.setPubDate(new Date());
            channel.setTitle("RSS feed for Twitter search for " + query);
            channel.setDescription("");
            channel.setLink("");
            RSSFeed feed = new RSSFeed(tl.size());
            feed.setChannel(channel);
            for (Tweet t: tl) {
                User u = tl.getUser(t);
                RSSMessage m = new RSSMessage();
                m.setLink(t.getStatusIdUrl().toExternalForm());
                m.setAuthor(u.getName() + " @" + u.getScreenName());
                m.setTitle(u.getName() + " @" + u.getScreenName());
                m.setDescription(t.getText());
                m.setPubDate(t.getCreatedAtDate());
                m.setGuid(t.getIdStr());
                feed.addMessage(m);
            }
            String rss = feed.toString();
            //System.out.println("feed has " + feed.size() + " entries");
            
            // write xml
            response.getOutputStream().write(rss.getBytes("UTF-8"));
        }
        DAO.log(path + "?" + request.getQueryString());
    }
}
