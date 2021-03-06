/*
 * Copyright 2013-2015 Erudika. http://erudika.com
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
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para;

import ch.qos.logback.access.jetty.RequestLogImpl;
import com.erudika.para.aop.AOPModule;
import com.erudika.para.cache.CacheModule;
import com.erudika.para.email.EmailModule;
import com.erudika.para.i18n.I18nModule;
import com.erudika.para.persistence.PersistenceModule;
import com.erudika.para.queue.QueueModule;
import com.erudika.para.rest.Api1;
import com.erudika.para.search.SearchModule;
import com.erudika.para.security.SecurityModule;
import com.erudika.para.storage.StorageModule;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.filters.CORSFilter;
import com.erudika.para.utils.filters.ErrorFilter;
import com.erudika.para.utils.filters.GZipServletFilter;
import com.google.inject.Module;
import javax.annotation.PreDestroy;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.MessageSourceAutoConfiguration;
import org.springframework.boot.builder.ParentContextApplicationContextInitializer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.embedded.AnnotationConfigEmbeddedWebApplicationContext;
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.FilterRegistrationBean;
import org.springframework.boot.context.embedded.ServletRegistrationBean;
import org.springframework.boot.context.embedded.jetty.JettyEmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.jetty.JettyServerCustomizer;
import org.springframework.boot.context.web.ServletContextApplicationContextInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;

/**
 * Para modules are initialized and destroyed from here.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
@Configuration
@EnableAutoConfiguration(exclude = { MessageSourceAutoConfiguration.class })
@ComponentScan
public class ParaServer implements WebApplicationInitializer, Ordered {

	private static final Logger logger = LoggerFactory.getLogger(ParaServer.class);

	public static Module[] getCoreModules() {
		return new Module[] {
			new AOPModule(),
			new CacheModule(),
			new EmailModule(),
			new I18nModule(),
			new PersistenceModule(),
			new QueueModule(),
			new SearchModule(),
			new StorageModule(),
			new SecurityModule()
		};
	}

	@Override
	public int getOrder() {
		return 1;
	}

	@Bean
	public ServletRegistrationBean jerseyRegistrationBean() {
		ServletRegistrationBean reg = new ServletRegistrationBean(
				new ServletContainer(new Api1()), Api1.PATH + "*");
		reg.setName(Api1.class.getSimpleName());
		reg.setAsyncSupported(true);
		reg.setEnabled(true);
		reg.setOrder(1);
		return reg;
	}

	@Bean
	public FilterRegistrationBean gzipFilterRegistrationBean() {
		FilterRegistrationBean frb = new FilterRegistrationBean(new GZipServletFilter());
		frb.addUrlPatterns("*.css", "*.json", "*.html", "*.js", Api1.PATH + "*");
		frb.setAsyncSupported(true);
		frb.setEnabled(Config.GZIP_ENABLED);
		frb.setMatchAfter(true);
		frb.setOrder(20);
		return frb;
	}

	@Bean
	public FilterRegistrationBean corsFilterRegistrationBean() {
		FilterRegistrationBean frb = new FilterRegistrationBean(new CORSFilter());
		frb.addUrlPatterns(Api1.PATH + "*");
		frb.addInitParameter("cors.allowed.methods", "GET,POST,PATCH,PUT,DELETE,HEAD,OPTIONS");
		frb.addInitParameter("cors.exposed.headers", "Cache-Control,Content-Length,Content-Type,Date,ETag,Expires");
		frb.addInitParameter("cors.allowed.headers", "Origin,Accept,X-Requested-With,Content-Type,"
				+ "Access-Control-Request-Method,Access-Control-Request-Headers,X-Amz-Credential,"
				+ "X-Amz-Date,Authorization");
		frb.setAsyncSupported(true);
		frb.setEnabled(Config.CORS_ENABLED);
		frb.setMatchAfter(true);
		frb.setOrder(3);
		return frb;
	}

	@Bean
	public EmbeddedServletContainerFactory jettyConfigBean() {
		JettyEmbeddedServletContainerFactory jef = new JettyEmbeddedServletContainerFactory();
		jef.addServerCustomizers(new JettyServerCustomizer() {
			public void customize(Server server) {
				if (Config.getConfigBoolean("access_log_enabled", true)) {
					// enable access log via Logback
					HandlerCollection handlers = new HandlerCollection();
					for (Handler handler : server.getHandlers()) {
						handlers.addHandler(handler);
					}
					RequestLogHandler reqLogs = new RequestLogHandler();
					reqLogs.setServer(server);
					RequestLogImpl rli = new RequestLogImpl();
					rli.setResource("/logback-access.xml");
					rli.setQuiet(true);
					reqLogs.setRequestLog(rli);
					handlers.addHandler(reqLogs);
					server.setHandler(handlers);
				}

				for (Connector y : server.getConnectors()) {
					for (ConnectionFactory cf : y.getConnectionFactories()) {
						if (cf instanceof HttpConnectionFactory) {
							HttpConnectionFactory dcf = (HttpConnectionFactory) cf;
							// support for X-Forwarded-Proto
							// redirect back to https if original request uses it
							if (Config.IN_PRODUCTION) {
								HttpConfiguration httpConfiguration = dcf.getHttpConfiguration();
								httpConfiguration.addCustomizer(new ForwardedRequestCustomizer());
							}
							// Disable Jetty version header
							dcf.getHttpConfiguration().setSendServerVersion(false);
						}
					}
				}
			}
		});
		return jef;
	}

	/**
	 * Called before shutdown.
	 */
	@PreDestroy
	public void preDestroy() {
		Para.destroy();
	}

	@Override
	public void onStartup(ServletContext sc) throws ServletException {
		runAsWAR(sc, ParaServer.class);
	}

	/**
	 * This is the initializing method when running ParaServer as WAR,
	 * deployed to a servlet container.
	 * @param sc the ServletContext instance
	 * @param sources the application classes that will be scanned
	 * @return the application context
	 */
	protected static WebApplicationContext runAsWAR(ServletContext sc, Object... sources) {
		ApplicationContext parent = null;
		Object object = sc.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
		if (object instanceof ApplicationContext) {
			logger.info("Root context already created (using as parent).");
			parent = (ApplicationContext) object;
			sc.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, null);
		}
		SpringApplicationBuilder application = new SpringApplicationBuilder(sources);
		if (parent != null) {
			application.initializers(new ParentContextApplicationContextInitializer(parent));
		}
		application.initializers(new ServletContextApplicationContextInitializer(sc));
		application.contextClass(AnnotationConfigEmbeddedWebApplicationContext.class);

		// entry point (WAR)
		application.web(true);
		application.showBanner(false);
		Para.initialize(getCoreModules());
		// Ensure error pages are registered
		application.sources(ErrorFilter.class);

		WebApplicationContext rootAppContext = (WebApplicationContext) application.run();

		if (rootAppContext != null) {
			sc.addListener(new ContextLoaderListener(rootAppContext) {
				@Override
				public void contextInitialized(ServletContextEvent event) {
					// no-op because the application context is already initialized
				}
			});
			sc.getSessionCookieConfig().setName("sess");
			sc.getSessionCookieConfig().setMaxAge(1);
			sc.getSessionCookieConfig().setHttpOnly(true);
			sc.setInitParameter("org.eclipse.jetty.servlet.Default.dirAllowed", "false");
		}
		return rootAppContext;
	}

	/**
	 * This is the initializing method when running ParaServer as executable JAR (or WAR),
	 * from the command line: java -jar para.jar
	 * @param args command line arguments array (same as those in {@code void main(String[] args)} )
	 * @param sources the application classes that will be scanned
	 */
	protected static void runAsJAR(String[] args, Object... sources) {
		// entry point (JAR)
		SpringApplication app = new SpringApplication(sources);
		app.setWebEnvironment(true);
		app.setShowBanner(false);
		Para.initialize(getCoreModules());
		app.run(args);
	}

	public static void main(String[] args) {
		runAsJAR(args, ParaServer.class);
	}
}
