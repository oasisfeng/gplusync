package com.oasisfeng.x3.gplusync;

import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import repacked.sun.misc.BASE64Encoder;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.conf.ConfigurationBuilder;

import com.google.appengine.api.urlfetch.HTTPHeader;
import com.google.appengine.api.urlfetch.HTTPMethod;
import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.appengine.api.urlfetch.URLFetchServiceFactory;
import com.oasisfeng.x3.xmpp.Notifier;

/** @author Oasis */
public class GPlus2Weibo extends HttpServlet {

	protected boolean extractURLWithoutProtocol = true;

	private static final Properties mConfig = new Properties();
	private static final URL KWeiboUpdateApiUrl;

	static {
		try { mConfig.load(GPlus2Weibo.class.getResourceAsStream("/gplusync.properties")); }
		catch (final IOException e) { throw new RuntimeException(e); }

		URL url; try { url = new URL("http://api.t.sina.com.cn/statuses/update.xml"); }
		catch (final MalformedURLException e) { throw new Error(e); }
		KWeiboUpdateApiUrl = url;
	}

	private static final String KSenderUserName = mConfig.getProperty("google.plus.user.nick") + " (Google+)";

	private static final int KMaxTweetLength = 140;
	private static final int KMaxWeiboTextLength = 280;
	private static final int KTwitterShortLinkLength = "https://t.co/12345678".length();
	private static final int KWeiboShortLinkLength = "http://t.cn/1234567".length();
	private static final String KTruncationTail = "… ";

	@Override
	public void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
		final Properties props = new Properties();
		final Session session = Session.getDefaultInstance(props, null);
		MimeMessage message;
		try {
			message = new MimeMessage(session, req.getInputStream());
			final Address[] from = message.getFrom();
			final Address[] recipients = message.getAllRecipients();
			if (from == null || from.length == 0 || recipients == null || recipients.length == 0) return;
			final InternetAddress email = (InternetAddress) from[0];
			final String recipient = ((InternetAddress) recipients[0]).getAddress();

			logMail(email, recipient, message);

			final String personal = email.getPersonal();
			if (personal == null || recipient == null || ! personal.equals(KSenderUserName)) return;

			String text = extractText(message);
			text = removeExplicitMentions(text);

			if (recipient.startsWith("plusync@")) {
				sendToTwitter(text, message);
				sendToWeibo(text, message);
			} else if (recipient.startsWith("plusync+twitter@"))
				sendToTwitter(text, message);
			else if (recipient.startsWith("plusync+weibo@"))
				sendToWeibo(text, message);
			else
				Notifier.send("Unknown recipient: " + recipient);
		} catch (final MessagingException e) {
			log.log(Level.SEVERE, "Error in parsing mail", e);
		}
	}

	private static final String KPlusSinaWeibo = "+Sina Weibo";
	private String removeExplicitMentions(String text) {
		text = text.trim();
		if (text.startsWith(KPlusSinaWeibo))
			text = text.substring(KPlusSinaWeibo.length()).trim();
		if (text.endsWith(KPlusSinaWeibo))
			text = text.substring(0, text.length() - KPlusSinaWeibo.length()).trim();
		return text;
	}

	private void sendToWeibo(final String aText, final MimeMessage aMessage) throws MalformedURLException,
			UnsupportedEncodingException, MessagingException, IOException {
		// Trim the text to the limit of weibo: 140 chars (as GBK)
		String text = aText;
		byte[] bytes = text.getBytes("GBK");
		if (bytes.length - calcLengthReductionWithURLsShortened(text) > KMaxWeiboTextLength) {
			final String link = extractLink(aMessage);
			if (link == null) throw new IllegalArgumentException("Malformed notification mail");
			bytes = Arrays.copyOfRange(bytes, 0, KMaxWeiboTextLength - KWeiboShortLinkLength - KTruncationTail.length());
			text = new String(bytes, "GBK") + KTruncationTail + link;
			Notifier.send("Truncated weibo: " + text);
		}

		if (text.startsWith("[test]")) return;

		final HTTPRequest request = new HTTPRequest(KWeiboUpdateApiUrl, HTTPMethod.POST);
		request.addHeader(new HTTPHeader("Authorization", "Basic " + iBase64.encode(iWeiboCredential.getBytes("US-ASCII"))));
		request.setPayload(("source=" + mConfig.getProperty("weibo.app.key") + "&status=" + URLEncoder.encode(text, "UTF-8")).getBytes("US-ASCII"));
		final HTTPResponse result = iURLFetch.fetch(request);
		if (result.getResponseCode() != 200) {
			final String msg = "Failed to post to Weibo: " + result.getResponseCode() + "\n" + new String(result.getContent(), "UTF-8");
			log.warning(msg);
			Notifier.send(msg);
		} else
			Notifier.send("To Weibo: " + text);
	}

	private void sendToTwitter(final String aText, final MimeMessage aMessage) throws MessagingException, IOException {
		String text = aText; //Normalizer.normalize(aText, Form.NFC);
		if (text.length() - calcLengthReductionWithURLsShortened(text) > KMaxTweetLength) {
		    final String link = extractLink(aMessage);
			if (link == null) throw new IllegalArgumentException("Malformed notification mail");
			text = text.substring(0, KMaxTweetLength - KTwitterShortLinkLength - KTruncationTail.length());
			text += KTruncationTail + link;
			Notifier.send("Truncated tweet: " + text);
		}

		if (text.startsWith("[test]")) return;

		try {
			iTwitter.updateStatus(text);
			Notifier.send("To Twitter: " + text);
		} catch (final TwitterException e) {
			log.log(Level.WARNING, "Failed to post to Twitter", e);
			Notifier.send("Failed to post to Twitter due to " + e);
		}
	}

	/** Extract full text from Google+ notification mail */
	private String extractText(final MimeMessage message) throws MessagingException, IOException {
		final Multipart parts = (Multipart) message.getContent();
		for (int num_parts = parts.getCount(), i = 0; i < num_parts; i ++) {
			final BodyPart part = parts.getBodyPart(i);
			final String content_type = part.getContentType();
			if (! content_type.startsWith("text/plain")) continue;
			final Object content = part.getContent();
			if (! (content instanceof String)) continue;
			final Matcher matcher = iTextPattern.matcher((String) content);
			if (! matcher.find()) {
				log.warning("Text patten not found in body part " + i + ": " + content);
				continue;
			}
			final String text = matcher.group(1);
			if (text == null) continue;
			// Trim all leading white spaces in every line.
			final String combined = text.replaceAll(" \\r\\n", "").replace("\\r\\n", " ");
			log.info(combined);
			return combined;
		}
		throw new EOFException();
	}

	private String extractLink(final MimeMessage message) throws MessagingException, IOException {
		final Multipart parts = (Multipart) message.getContent();
		for (int num_parts = parts.getCount(), i = 0; i < num_parts; i ++) {
			final BodyPart part = parts.getBodyPart(i);
			final String content_type = part.getContentType();
			if (! content_type.startsWith("text/plain")) continue;
			final Object content = part.getContent();
			if (! (content instanceof String)) continue;
			final Matcher matcher = iLinkPattern.matcher((String) content);
			if (! matcher.find()) {
				log.warning("Link patten not found in body part " + i + ": " + content);
				continue;
			}
			final String link_path = matcher.group(1);
			if (link_path == null) continue;
			final String link = "https://plus.google.com/u/0" + URLDecoder.decode(link_path, "UTF-8");
			log.info(link);
			return link;
		}
		return null;
	}

	/** Calculate length of shortened content with wrapped URLs. */
	private int calcLengthReductionWithURLsShortened(final String text) {
		int length_reduction = 0;
		for (final String url : extractUnwrappedURLs(text))
			length_reduction += url.length() - KTwitterShortLinkLength;
		return length_reduction;
	}

	/** Borrowed from project https://github.com/twitter/twitter-text-java with minor modifications */
	private List<String> extractUnwrappedURLs(final String text) {
		if (text == null || text.isEmpty() || (extractURLWithoutProtocol ? text.indexOf('.') : text.indexOf(':')) == -1) {
			// Performance optimization.
			// If text doesn't contain '.' or ':' at all, text doesn't contain URL,
			// so we can simply return an empty list.
			return Collections.emptyList();
		}
		final List<String> urls = new ArrayList<String>();
		final Matcher matcher = Regex.VALID_URL.matcher(text);
		while (matcher.find()) {
			if (matcher.group(Regex.VALID_URL_GROUP_PROTOCOL) == null)
				// skip if protocol is not present and 'extractURLWithoutProtocol' is false
				// or URL is preceded by invalid character.
				if (!extractURLWithoutProtocol
						|| Regex.INVALID_URL_WITHOUT_PROTOCOL_MATCH_BEGIN.matcher(
								matcher.group(Regex.VALID_URL_GROUP_BEFORE)).matches())
					continue;
			final String url = matcher.group(Regex.VALID_URL_GROUP_URL);
			final Matcher tco_matcher = Regex.VALID_TCO_URL.matcher(url);
			if (tco_matcher.find()) continue;	// Skip already wrapped URLs
			urls.add(url);
		}
		return urls;
	}

	private void logMail(final InternetAddress aFrom, final String aRecipient, final MimeMessage message) throws MessagingException, IOException {
		log.info("Mail from \"" + aFrom.getPersonal() + " <" + aFrom.getAddress() + ">\" to " + aRecipient + "\nSubject: " + message.getSubject());
		final Object content = message.getContent();
		if (content instanceof String)
			log.info("Mail content: " + (String) content);
		else if (content instanceof Multipart) {
			final Multipart multiparts = (Multipart) content;
			for (int num_parts = multiparts.getCount(), i = 0; i < num_parts; i ++) {
				final BodyPart part = multiparts.getBodyPart(i);
				log.info("Part " + i + " (" + part.getContentType() + "): " + part.getContent());
			}
		} else
			log.warning("Unknown content type: " + message.getContentType() + " (" + content.getClass().getSimpleName() + ")");
	}

	private static final String iWeiboCredential = mConfig.getProperty("weibo.username") + ":" + mConfig.getProperty("weibo.password");
	private static final URLFetchService iURLFetch = URLFetchServiceFactory.getURLFetchService();
	private static final BASE64Encoder iBase64 = new BASE64Encoder();
	/** Pattern for extracting post text from an Google+ notification mail body */
	private static final Pattern iTextPattern = Pattern.compile("^ *\"(.*)\" *$", Pattern.MULTILINE | Pattern.DOTALL);
	private static final Pattern iLinkPattern = Pattern.compile("://plus\\.google\\.com/.*&path=([0-9a-zA-Z%]*)&.*");
	private static final Twitter iTwitter = new TwitterFactory(new ConfigurationBuilder()
			.setOAuthConsumerKey(mConfig.getProperty("twitter.consumer.key"))
			.setOAuthConsumerSecret(mConfig.getProperty("twitter.consumer.secret"))
			.setOAuthAccessToken(mConfig.getProperty("twitter.access.token"))
			.setOAuthAccessTokenSecret(mConfig.getProperty("twitter.access.secret")).build()).getInstance();
	private static final Logger log = Logger.getLogger(GPlus2Weibo.class.getName());
	private static final long serialVersionUID = 1L;
}