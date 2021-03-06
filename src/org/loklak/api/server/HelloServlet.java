/**
 *  HelloServlet
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
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.loklak.DAO;
import org.loklak.api.RemoteAccess;
import org.loklak.api.ServletHelper;

/**
 * Servlet to span the message peer-to-peer network.
 * This servlet is called to announce the existence of the remote peer.
 */
public class HelloServlet extends HttpServlet {
    
    private static final long serialVersionUID = 1839868262296635665L;

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
        Map<String, String> qm = ServletHelper.getQueryMap(request.getQueryString());
        String httpports = qm == null ? request.getParameter("port.http") : qm.get("port.http");
        Integer httpport = httpports == null ? null : Integer.parseInt(httpports);
        String httpsports = qm == null ? request.getParameter("port.https") : qm.get("port.https");
        Integer httpsport = httpsports == null ? null : Integer.parseInt(httpsports);
        RemoteAccess.log(clientHost, path, httpport, httpsport);
        if (time_since_last_access < DAO.getConfig("DoS.blackout", 1000)) {response.sendError(503, "your request frequency is too high"); return;}
        
        String callback = qm == null ? request.getParameter("callback") : qm.get("callback");
        boolean jsonp = callback != null && callback.length() > 0;
        // String pingback = qm == null ? request.getParameter("pingback") : qm.get("pingback");
        // pingback may be either filled with nothing, the term 'now' or the term 'later'

        response.setDateHeader("Last-Modified", now);
        response.setDateHeader("Expires", now);
        response.setContentType("application/javascript");
        response.setHeader("X-Robots-Tag",  "noindex,noarchive,nofollow,nosnippet");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);
        
        // generate json
        XContentBuilder json = XContentFactory.jsonBuilder().prettyPrint().lfAtEnd();
        json.startObject();
        json.field("status", "ok");
        json.endObject();

        // write json
        ServletOutputStream sos = response.getOutputStream();
        if (jsonp) sos.print(callback + "(");
        sos.print(json.string());
        if (jsonp) sos.println(");");
        sos.println();

        DAO.log(path + "?" + request.getQueryString());
    }
    
}
