/*
 * Copyright 2016 Aaron Knight
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.slkdev.swagger.confluence.service.impl;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;
import net.slkdev.swagger.confluence.config.SwaggerConfluenceConfig;
import net.slkdev.swagger.confluence.constants.PageType;
import net.slkdev.swagger.confluence.constants.PaginationMode;
import net.slkdev.swagger.confluence.exception.ConfluenceAPIException;
import net.slkdev.swagger.confluence.exception.SwaggerConfluenceConfigurationException;
import net.slkdev.swagger.confluence.model.ConfluenceLink;
import net.slkdev.swagger.confluence.model.ConfluenceLinkBuilder;
import net.slkdev.swagger.confluence.model.ConfluencePage;
import net.slkdev.swagger.confluence.model.ConfluencePageBuilder;
import net.slkdev.swagger.confluence.service.XHtmlToConfluenceService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

import static net.minidev.json.parser.JSONParser.DEFAULT_PERMISSIVE_MODE;
import static net.slkdev.swagger.confluence.constants.PaginationMode.INDIVIDUAL_PAGES;
import static net.slkdev.swagger.confluence.constants.PaginationMode.SINGLE_PAGE;
import static org.jsoup.nodes.Entities.EscapeMode.xhtml;

public class XHtmlToConfluenceServiceImpl implements XHtmlToConfluenceService {

    private static final Logger LOG = LoggerFactory.getLogger(XHtmlToConfluenceServiceImpl.class);
    private static final ThreadLocal<SwaggerConfluenceConfig> SWAGGER_CONFLUENCE_CONFIG = new ThreadLocal<>();
    private static final ThreadLocal<Document> SWAGGER_DOCUMENT = new ThreadLocal<>();

    private RestTemplate restTemplate;

    public XHtmlToConfluenceServiceImpl(final RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public void postXHtmlToConfluence(final SwaggerConfluenceConfig swaggerConfluenceConfig, final String xhtml) {
        LOG.info("Posting XHTML to Confluence...");
        SWAGGER_CONFLUENCE_CONFIG.set(swaggerConfluenceConfig);
        SWAGGER_DOCUMENT.set(parseXhtml(xhtml));

        final Map<String, ConfluenceLink> titleLinkMap = buildTableOfContentsLinkMap();
        final List<ConfluencePage> confluencePages = handlePagination();

        Integer categoryAncestorId = null;
        Integer individualAncestorId = null;

        for (final ConfluencePage confluencePage : confluencePages) {
            final PageType pageType = confluencePage.getPageType();

            LOG.debug("PROCESSING PAGE: {} --> {}", confluencePage.getPageType(), confluencePage.getXhtml());

            switch (pageType) {
                case ROOT:
                    confluencePage.setAncestorId(swaggerConfluenceConfig.getAncestorId());
                    break;
                case CATEGORY:
                    confluencePage.setAncestorId(categoryAncestorId);
                    break;
                case INDIVIDUAL:
                    confluencePage.setAncestorId(individualAncestorId);
                    break;
                default:
                    throw new SwaggerConfluenceConfigurationException("Unknown Page Type Encountered!");
            }

            addExistingPageData(confluencePage);

            if (confluencePage.exists()) {
                updatePage(confluencePage, titleLinkMap);
            } else {
                createPage(confluencePage, titleLinkMap);
            }

            if (pageType == PageType.ROOT) {
                categoryAncestorId = confluencePage.getAncestorId();
            } else if (pageType == PageType.CATEGORY) {
                individualAncestorId = confluencePage.getAncestorId();
            }
        }
    }

    private Map<String, ConfluenceLink> buildTableOfContentsLinkMap() {
        final Map<String, ConfluenceLink> titleLinkMap = new HashMap<>();

        final Document document = SWAGGER_DOCUMENT.get();
        final Elements tocElements = document.select(".toc");

        final Elements tocCategoryElements = tocElements.select(".sectlevel1").first().children();

        final Elements tocFilteredCategoryElements = new Elements();

        for (final Element tocCategoryElement : tocCategoryElements) {
            final Element categoryLinkElement = tocCategoryElement.children().first();
            tocFilteredCategoryElements.add(categoryLinkElement);
        }

        final Elements tocIndividualElements = tocElements.select(".sectlevel2");

        addLinksByType(titleLinkMap, tocFilteredCategoryElements, PageType.CATEGORY, null);

        int categoryCount = 1;

        for (final Element tocIndividualElement : tocIndividualElements) {
            final Elements tocIndividualElementLinks = tocIndividualElement.select("a");
            addLinksByType(titleLinkMap, tocIndividualElementLinks, PageType.INDIVIDUAL, categoryCount);
            categoryCount++;
        }

        return titleLinkMap;
    }

    private void addLinksByType(final Map<String, ConfluenceLink> confluenceLinkMap,
                                final Elements elements, final PageType pageType,
                                final Integer numericPrefix) {
        final SwaggerConfluenceConfig swaggerConfluenceConfig = SWAGGER_CONFLUENCE_CONFIG.get();

        int linkCount = 1;

        for (final Element element : elements) {
            final String confluenceLinkMarkup;
            final String originalTarget = element.attr("href");
            final String text = element.text();
            final String confluencePageTitle;

            if (pageType == PageType.INDIVIDUAL) {
                confluencePageTitle = buildConfluenceTitle(text, numericPrefix, linkCount);
            } else {
                confluencePageTitle = buildConfluenceTitle(text, linkCount, null);
            }

            switch (swaggerConfluenceConfig.getPaginationMode()) {
                case SINGLE_PAGE:
                    confluenceLinkMarkup = String.format(
                            "<ac:link ac:anchor=\"%s\" />", text
                    );
                    break;

                case CATEGORY_PAGES:
                    if (pageType == PageType.INDIVIDUAL) {
                        final String definitionsPageTitle;

                        if (swaggerConfluenceConfig.isGenerateNumericPrefixes()) {
                            definitionsPageTitle = String.format("3. %sDefinitions",
                                    swaggerConfluenceConfig.getPrefix());
                        } else {
                            definitionsPageTitle = String.format("%sDefinitions",
                                    swaggerConfluenceConfig.getPrefix());
                        }

                        confluenceLinkMarkup = String.format(
                                "<ac:link ac:anchor=\"%s\">\n" +
                                        "<ri:page ri:content-title=\"%s\" ri:space-key=\"%s\"/>" +
                                        "<ac:link-body>\n" +
                                        "<![CDATA[%s]]>\n" +
                                        "</ac:link-body>\n" +
                                        "</ac:link>", text, definitionsPageTitle,
                                swaggerConfluenceConfig.getSpaceKey(), text
                        );
                    } else {
                        confluenceLinkMarkup = String.format(
                                "<ac:link>\n" +
                                        "<ri:page ri:content-title=\"%s\" ri:space-key=\"%s\"/>" +
                                        "<ac:link-body>\n" +
                                        "<![CDATA[%s]]>\n" +
                                        "</ac:link-body>\n" +
                                        "</ac:link>", confluencePageTitle,
                                swaggerConfluenceConfig.getSpaceKey(), text
                        );
                    }
                    break;

                case INDIVIDUAL_PAGES:
                    confluenceLinkMarkup = String.format(
                            "<ac:link>\n" +
                                    "<ri:page ri:content-title=\"%s\" ri:space-key=\"%s\"/>" +
                                    "<ac:link-body>\n" +
                                    "<![CDATA[%s]]>\n" +
                                    "</ac:link-body>\n" +
                                    "</ac:link>", confluencePageTitle,
                            swaggerConfluenceConfig.getSpaceKey(), text
                    );
                    break;

                default:
                    throw new SwaggerConfluenceConfigurationException("Unhandled Pagination Mode!");
            }

            final ConfluenceLink confluenceLink = ConfluenceLinkBuilder.aConfluenceLink()
                    .withPageType(pageType)
                    .withOriginalHref(originalTarget)
                    .withText(text)
                    .withConfluenceLinkMarkup(confluenceLinkMarkup)
                    .build();

            LOG.debug("LINK MAP: {} -> {}", originalTarget, confluenceLinkMarkup);

            confluenceLinkMap.put(originalTarget, confluenceLink);

            linkCount++;
        }
    }

    private static Document parseXhtml(final String inputXhtml) {
        final Document originalDocument = Jsoup.parse(inputXhtml, "utf-8", Parser.xmlParser());
        originalDocument.outputSettings().prettyPrint(false);
        originalDocument.outputSettings().escapeMode(xhtml);
        originalDocument.outputSettings().charset("UTF-8");

        return originalDocument;
    }

    private static List<ConfluencePage> handlePagination() {
        final List<ConfluencePage> confluencePages = new ArrayList<>();
        final SwaggerConfluenceConfig swaggerConfluenceConfig = SWAGGER_CONFLUENCE_CONFIG.get();

        final PaginationMode paginationMode = swaggerConfluenceConfig.getPaginationMode();

        final Document originalDocument = SWAGGER_DOCUMENT.get();
        final Document transformedDocument = originalDocument.clone();

        final Elements categoryElements = transformedDocument.select(".sect1");

        // Remove ToC form the transformed document
        final Elements toc = transformedDocument.select(".toc");
        toc.html("");
        toc.unwrap();

        // For Single Page Mode, the incoming XHTML can be used directly.
        if (paginationMode == SINGLE_PAGE) {
            final ConfluencePage confluencePage = ConfluencePageBuilder.aConfluencePage()
                    .withPageType(PageType.ROOT)
                    .withOriginalTitle(swaggerConfluenceConfig.getTitle())
                    .withConfluenceTitle(buildConfluenceTitle(swaggerConfluenceConfig.getTitle(), null, null))
                    .withXhtml(transformedDocument.html()).build();
            confluencePages.add(confluencePage);

            return confluencePages;
        }

        // Before beginning further processing, we need to know if we're in individual
        // page mode or not, as that will effect how we split the DOM. If we're in this
        // mode then the category pages will contain inner table of contents.
        final boolean individualPages = (paginationMode == INDIVIDUAL_PAGES);

        // From here on, if we're still proceeding then we know the meat of the document
        // will go in sub-pages. So for the master page, we will use the table of contents
        final Elements tocElements = originalDocument.select(".toc");

        final List<String> innerTocXHtmlList = new ArrayList<>();
        final Elements innerTocElements = originalDocument.select(".sectlevel2");

        for (final Element innerTocElement : innerTocElements) {
            // If we're in individual page mode, then we collect the inner ToCs
            if (individualPages) {
                innerTocXHtmlList.add(innerTocElement.html());
            }
            // If we're in category page mode, then we strip out the inner table of contents.
            else {
                innerTocElement.html("");
                innerTocElement.unwrap();
            }
        }

        // Build the Root Page w/ the Appropriate Level of Table of Contents
        final ConfluencePage rootConfluencePage = ConfluencePageBuilder.aConfluencePage()
                .withPageType(PageType.ROOT)
                .withOriginalTitle(swaggerConfluenceConfig.getTitle())
                .withConfluenceTitle(buildConfluenceTitle(swaggerConfluenceConfig.getTitle(), null, null))
                .withXhtml(tocElements.html()).build();
        confluencePages.add(rootConfluencePage);

        int category = 1;

        // Now we process the category pages
        for (final Element categoryElement : categoryElements) {
            // Fetch the title from the first child, which is the header element
            final String categoryTitle = categoryElement.children().first().text();

            // If we're in individual mode then we need these to be sub table of contents
            if (individualPages) {


                final ConfluencePage categoryConfluencePage = ConfluencePageBuilder.aConfluencePage()
                        .withPageType(PageType.CATEGORY)
                        .withOriginalTitle(categoryTitle)
                        .withConfluenceTitle(buildConfluenceTitle(categoryTitle, category, null))
                        .withXhtml(innerTocXHtmlList.get(category - 1)).build();
                confluencePages.add(categoryConfluencePage);

                final Elements individualElements = categoryElement.getElementsByClass("sect2");

                int individual = 1;

                for (final Element individualElement : individualElements) {
                    final String individualTitle = individualElement.children().first().text();
                    final ConfluencePage individualConfluencePage = ConfluencePageBuilder.aConfluencePage()
                            .withPageType(PageType.INDIVIDUAL)
                            .withOriginalTitle(individualTitle)
                            .withConfluenceTitle(buildConfluenceTitle(individualTitle, category, individual))
                            .withXhtml(individualElement.html()).build();
                    confluencePages.add(individualConfluencePage);

                    individual++;
                }

                category++;
                continue;
            }

            // If we're in category mode, we use the remaining page data
            final ConfluencePage categoryConfluencePage = ConfluencePageBuilder.aConfluencePage()
                    .withPageType(PageType.CATEGORY)
                    .withOriginalTitle(categoryTitle)
                    .withConfluenceTitle(buildConfluenceTitle(categoryTitle, category, null))
                    .withXhtml(categoryElement.html()).build();
            confluencePages.add(categoryConfluencePage);

            category++;
        }

        return confluencePages;
    }

    private static String buildConfluenceTitle(final String originalTitle, final Integer category,
                                        final Integer individual) {
        final SwaggerConfluenceConfig swaggerConfluenceConfig = SWAGGER_CONFLUENCE_CONFIG.get();

        final StringBuilder confluenceTitleBuilder = new StringBuilder();

        if (category != null && swaggerConfluenceConfig.isGenerateNumericPrefixes()) {
            confluenceTitleBuilder.append(category);

            if (individual != null) {
                confluenceTitleBuilder.append('.');
                confluenceTitleBuilder.append(individual);
            }

            confluenceTitleBuilder.append(". ");
        }

        confluenceTitleBuilder.append(swaggerConfluenceConfig.getPrefix());
        confluenceTitleBuilder.append(originalTitle);

        return confluenceTitleBuilder.toString();
    }

    private void addExistingPageData(final ConfluencePage confluencePage) {
        final SwaggerConfluenceConfig swaggerConfluenceConfig = SWAGGER_CONFLUENCE_CONFIG.get();

        final HttpHeaders httpHeaders = buildHttpHeaders(swaggerConfluenceConfig.getAuthentication());
        final HttpEntity<String> requestEntity = new HttpEntity<>(httpHeaders);

        final URI targetUrl = UriComponentsBuilder.fromUriString(swaggerConfluenceConfig.getConfluenceRestApiUrl())
                .path("/content")
                .queryParam("spaceKey", swaggerConfluenceConfig.getSpaceKey())
                .queryParam("title", confluencePage.getConfluenceTitle())
                .queryParam("expand", "body.storage,version,ancestors")
                .build()
                .toUri();

        final ResponseEntity<String> responseEntity = restTemplate.exchange(targetUrl,
                HttpMethod.GET, requestEntity, String.class);

        final String jsonBody = responseEntity.getBody();

        try {
            LOG.debug("GET RESPONSE: {}", jsonBody);

            final String id = JsonPath.read(jsonBody, "$.results[0].id");
            final Integer version = JsonPath.read(jsonBody, "$.results[0].version.number");

            final JSONArray ancestors = JsonPath.read(jsonBody, "$.results[0].ancestors");

            if (!ancestors.isEmpty()) {
                final Map<String, Object> lastAncestor = (Map<String, Object>) ancestors.get(ancestors.size() - 1);
                final Integer ancestorId = Integer.valueOf((String) lastAncestor.get("id"));

                LOG.debug("ANCESTORS: {} : {}, CHOSE -> {}", ancestors.getClass().getName(), ancestors, ancestorId);
                confluencePage.setAncestorId(ancestorId);
            }

            confluencePage.setId(id);
            confluencePage.setVersion(version);
            confluencePage.setExists(true);

            LOG.info("Page {} Already Exists, Performing an Update!", confluencePage.getId());

        } catch (final PathNotFoundException e) {
            confluencePage.setExists(false);

            LOG.info("Page Does Not Exist, Creating a New Page!");

            // Prevent New Pages from Being Orphaned if there was no ancestor id
            // specified by querying for the id of the space root. Confluence
            // does not do this automatically, and thus you would otherwise not be
            // able to navigate to the page unless you manually knew the URL
            if (confluencePage.getAncestorId() == null) {
                final ConfluencePage spaceRootPage = ConfluencePageBuilder.aConfluencePage()
                        .withConfluenceTitle(swaggerConfluenceConfig.getSpaceKey())
                        .withOriginalTitle(swaggerConfluenceConfig.getSpaceKey())
                        .build();
                addExistingPageData(spaceRootPage);

                final Integer spaceRootAncestorId = Integer.valueOf(spaceRootPage.getId());

                LOG.info("ORPHAN PREVENTION FAIL SAFE: Using Space Root Ancestor Id {}",
                        spaceRootAncestorId);

                confluencePage.setAncestorId(spaceRootAncestorId);
            }
        }
    }

    private static HttpHeaders buildHttpHeaders(final String confluenceAuthentication) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", String.format("Basic %s", confluenceAuthentication));
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);

        return headers;
    }

    private Integer getPageIdFromResponse(final HttpEntity<String> responseEntity) {
        final String responseJson = responseEntity.getBody();
        final JSONParser jsonParser = new JSONParser(DEFAULT_PERMISSIVE_MODE);

        try {
            final JSONObject response = jsonParser.parse(responseJson, JSONObject.class);
            return Integer.valueOf((String) response.get("id"));
        } catch (ParseException e) {
            throw new ConfluenceAPIException("Error Parsing JSON Response from Confluence!", e);
        }
    }

    private void createPage(final ConfluencePage page, final Map<String, ConfluenceLink> confluenceLinkMap) {
        final SwaggerConfluenceConfig swaggerConfluenceConfig = SWAGGER_CONFLUENCE_CONFIG.get();
        final URI targetUrl = UriComponentsBuilder.fromUriString(swaggerConfluenceConfig.getConfluenceRestApiUrl())
                .path("/content")
                .build()
                .toUri();

        final HttpHeaders httpHeaders = buildHttpHeaders(swaggerConfluenceConfig.getAuthentication());
        final String formattedXHtml = reformatXHtml(page.getXhtml(), confluenceLinkMap);
        final String jsonPostBody = buildPostBody(page.getAncestorId(), page.getConfluenceTitle(), formattedXHtml).toJSONString();

        LOG.debug("CREATE PAGE REQUEST: {}", jsonPostBody);

        final HttpEntity<String> requestEntity = new HttpEntity<>(jsonPostBody, httpHeaders);

        final HttpEntity<String> responseEntity = restTemplate.exchange(targetUrl,
                HttpMethod.POST, requestEntity, String.class);

        LOG.debug("CREATE PAGE RESPONSE: {}", responseEntity.getBody());

        final Integer pageId = getPageIdFromResponse(responseEntity);
        page.setAncestorId(pageId);
    }

    private void updatePage(final ConfluencePage page, final Map<String, ConfluenceLink> confluenceLinkMap) {
        final SwaggerConfluenceConfig swaggerConfluenceConfig = SWAGGER_CONFLUENCE_CONFIG.get();

        final URI targetUrl = UriComponentsBuilder.fromUriString(swaggerConfluenceConfig.getConfluenceRestApiUrl())
                .path(String.format("/content/%s", page.getId()))
                .build()
                .toUri();

        final HttpHeaders httpHeaders = buildHttpHeaders(swaggerConfluenceConfig.getAuthentication());

        final JSONObject postVersionObject = new JSONObject();
        postVersionObject.put("number", page.getVersion() + 1);

        final String formattedXHtml = reformatXHtml(page.getXhtml(), confluenceLinkMap);
        final JSONObject postBody = buildPostBody(page.getAncestorId(), page.getConfluenceTitle(), formattedXHtml);
        postBody.put("id", page.getId());
        postBody.put("version", postVersionObject);

        final HttpEntity<String> requestEntity = new HttpEntity<>(postBody.toJSONString(), httpHeaders);

        LOG.debug("UPDATE PAGE REQUEST: {}", postBody);

        final HttpEntity<String> responseEntity = restTemplate.exchange(targetUrl, HttpMethod.PUT, requestEntity, String.class);

        LOG.debug("UPDATE PAGE RESPONSE: {}", responseEntity.getBody());
    }

    private static JSONObject buildPostBody(final Integer ancestorId, final String confluenceTitle, final String xhtml) {
        final SwaggerConfluenceConfig swaggerConfluenceConfig = SWAGGER_CONFLUENCE_CONFIG.get();

        final JSONObject jsonSpaceObject = new JSONObject();
        jsonSpaceObject.put("key", swaggerConfluenceConfig.getSpaceKey());

        final JSONObject jsonStorageObject = new JSONObject();
        jsonStorageObject.put("value", xhtml);
        jsonStorageObject.put("representation", "storage");

        final JSONObject jsonBodyObject = new JSONObject();
        jsonBodyObject.put("storage", jsonStorageObject);

        final JSONObject jsonObject = new JSONObject();
        jsonObject.put("type", "page");
        jsonObject.put("title", confluenceTitle);
        jsonObject.put("space", jsonSpaceObject);
        jsonObject.put("body", jsonBodyObject);

        if (ancestorId != null) {
            final JSONObject ancestor = new JSONObject();
            ancestor.put("type", "page");
            ancestor.put("id", ancestorId);

            final JSONArray ancestors = new JSONArray();
            ancestors.add(ancestor);

            jsonObject.put("ancestors", ancestors);
        }

        return jsonObject;
    }

    private static String reformatXHtml(final String inputXhtml, final Map<String, ConfluenceLink> confluenceLinkMap) {
        final Document document = Jsoup.parse(inputXhtml, "utf-8", Parser.xmlParser());
        document.outputSettings().prettyPrint(false);
        document.outputSettings().escapeMode(xhtml);
        document.outputSettings().charset("UTF-8");

        final Elements linkElements = document.select("a");

        for (final Element linkElement : linkElements) {
            final String originalHref = linkElement.attr("href");
            final ConfluenceLink confluenceLink = confluenceLinkMap.get(originalHref);

            if (confluenceLink == null) {
                LOG.debug("NO LINK MAPPING FOUND TO COVERT LINK: {}", originalHref);
                continue;
            }

            final String confluenceLinkMarkup = confluenceLink.getConfluenceLinkMarkup();

            LOG.debug("LINK CONVERSION: {} -> {}", originalHref, confluenceLinkMarkup);

            linkElement.before(confluenceLinkMarkup);

            linkElement.html("");
            linkElement.unwrap();
        }

        reformatXHtmlHeadings(document, "h2");
        reformatXHtmlHeadings(document, "h3");
        reformatXHtmlHeadings(document, "#toctitle");

        reformatXHtmlSpacing(document.select(".sect2"));
        reformatXHtmlSpacing(document.select(".sect3"));

        return document.html();
    }

    private static void reformatXHtmlHeadings(final Document document, final String selector){
        final Elements elements = document.select(selector);

        for(final Element element : elements){
            final String text = element.text();
            final String strongHeaderText = String.format("<strong>%s</strong>", text);
            element.html(strongHeaderText);
        }
    }

    private static void reformatXHtmlSpacing(final Elements elements){
        for(final Element element : elements){
            element.before("<br />");
        }
    }

}
