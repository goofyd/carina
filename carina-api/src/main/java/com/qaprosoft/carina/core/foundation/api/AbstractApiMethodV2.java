/*******************************************************************************
 * Copyright 2020-2022 Zebrunner Inc (https://www.zebrunner.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.qaprosoft.carina.core.foundation.api;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.qaprosoft.apitools.builder.PropertiesProcessor;
import com.qaprosoft.apitools.validation.JsonComparatorContext;
import com.qaprosoft.apitools.validation.JsonKeywordsComparator;
import com.qaprosoft.apitools.validation.JsonValidator;
import com.qaprosoft.apitools.validation.XmlCompareMode;
import com.qaprosoft.apitools.validation.XmlValidator;
import com.qaprosoft.carina.core.foundation.api.http.HttpResponseStatusType;
import com.qaprosoft.carina.core.foundation.api.log.LoggingOutputStream;
import com.qaprosoft.carina.core.foundation.api.resolver.ContextResolverChain;
import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qaprosoft.apitools.builder.PropertiesProcessorMain;
import com.qaprosoft.apitools.message.TemplateMessage;
import com.qaprosoft.carina.core.foundation.api.annotation.ContentType;

import io.restassured.response.Response;

public abstract class AbstractApiMethodV2 extends AbstractApiMethod {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    
    private static final String ACCEPT_ALL_HEADER = "Accept=*/*";

    private Properties properties;
    private List<Class<? extends PropertiesProcessor>> ignoredPropertiesProcessorClasses;
    private String rqPath;
    private String rsPath;
    private String actualRsBody;

    /**
     * When this constructor is called then paths to request and expected response templates are taken from @RequestTemplatePath
     * and @ResponseTemplatePath if present
     */
    public AbstractApiMethodV2() {
        this(null, null);
    }

    public AbstractApiMethodV2(String rqPath, String rsPath) {
        this(rqPath, rsPath, new Properties());
    }

    public AbstractApiMethodV2(String rqPath, String rsPath, String propertiesPath) {
        this(rqPath, rsPath, loadProperties(propertiesPath));
    }

    public AbstractApiMethodV2(String rqPath, String rsPath, Properties properties) {
        super();
        setHeaders(ACCEPT_ALL_HEADER);
        setProperties(properties);
        initPaths(rqPath, rsPath);
    }

    private void initPaths(String rqPath, String rsPath) {
        this.rqPath = rqPath != null
                ? rqPath
                : ContextResolverChain.resolveRequestTemplatePath(this.getClass()).orElse(null);
        this.rsPath = rsPath != null
                ? rsPath
                : ContextResolverChain.resolveResponseTemplatePath(this.getClass()).orElse(null);
    }

    /**
     * Sets path to freemarker template for request body
     * 
     * @param path String
     */
    public void setRequestTemplate(String path) {
        this.rqPath = path;
    }

    /**
     * Sets path to freemarker template for expected response body
     * 
     * @param path String
     */
    public void setResponseTemplate(String path) {
        this.rsPath = path;
    }

    private void initBodyContent() {
        if (rqPath != null) {
            TemplateMessage tm = new TemplateMessage();
            tm.setIgnoredPropertiesProcessorClasses(ignoredPropertiesProcessorClasses);
            tm.setTemplatePath(rqPath);
            tm.setPropertiesStorage(properties);
            setBodyContent(tm.getMessageText());
        }
    }

    @Override
    public Response callAPI() {
        initBodyContent();
        Response rs = super.callAPI();
        actualRsBody = rs.asString();
        return rs;
    }

    @Override
    Response callAPI(LoggingOutputStream outputStream) {
        initBodyContent();
        Response rs = super.callAPI(outputStream);
        actualRsBody = rs.asString();
        return rs;
    }

    /**
     * Allows to create an api request with repetition, timeout and condition of successful response, as well as setting
     * a logging strategy
     *
     * @return APIMethodPoller object
     */
    public APIMethodPoller callAPIWithRetry() {
        initBodyContent();
        return APIMethodPoller.builder(this)
                .doAfterExecute(response -> actualRsBody = response.asString());
    }

    /**
     * Calls API expecting http status in response taken from @SuccessfulHttpStatus value
     * 
     * @return restassured Response object
     */
    public Response callAPIExpectSuccess() {
        HttpResponseStatusType statusType = ContextResolverChain.resolveSuccessfulHttpStatus(this.getClass())
                .orElseThrow(() -> new RuntimeException("To use this method please declare @SuccessfulHttpStatus for your AbstractApiMethod class"));
        expectResponseStatus(statusType);
        return callAPI();
    }

    /**
     * Sets path to .properties file which stores properties list for declared API method
     * 
     * @param propertiesPath String path to properties file
     */
    public void setProperties(String propertiesPath) {
        Properties properties = loadProperties(propertiesPath);
        setProperties(properties);
    }

    private static Properties loadProperties(String propertiesPath) {
        Properties properties;

        if (propertiesPath == null) {
            properties = new Properties();
        } else {
            URL baseResource = ClassLoader.getSystemResource(propertiesPath);
            if (baseResource != null) {
                properties = new Properties();
                try (InputStream propertiesStream = baseResource.openStream()) {
                    properties.load(propertiesStream);
                } catch (IOException e) {
                    throw new RuntimeException("Properties can't be loaded by path: " + propertiesPath, e);
                }
                LOGGER.info("Base properties loaded: " + propertiesPath);
            } else {
                throw new RuntimeException("Properties can't be found by path: " + propertiesPath);
            }
        }
        return properties;
    }

    public void ignorePropertiesProcessor(Class<? extends PropertiesProcessor> ignoredPropertiesProcessorClass) {
        if (this.ignoredPropertiesProcessorClasses == null) {
            this.ignoredPropertiesProcessorClasses = new ArrayList<>();
        }
        this.ignoredPropertiesProcessorClasses.add(ignoredPropertiesProcessorClass);
    }

    /**
     * Sets properties list for declared API method
     * 
     * @param properties Properties object with predefined properties for declared API method
     */
    public void setProperties(Properties properties) {
        if (properties != null) {
            this.properties = PropertiesProcessorMain.processProperties(properties, ignoredPropertiesProcessorClasses);
        }
    }

    public void addProperty(String key, Object value) {
        if (properties == null) {
            throw new RuntimeException("API method properties are not initialized!");
        }
        properties.put(key, value);
    }

    public void removeProperty(String key) {
        if (properties == null) {
            throw new RuntimeException("API method properties are not initialized!");
        }
        properties.remove(key);
    }

    public Properties getProperties() {
        return properties;
    }

    /**
     * Validates JSON response using custom options
     *
     * @param mode
     *            - determines how to compare 2 JSONs. See type description for more details. Mode is not applied for
     *            arrays comparison
     * @param validationFlags
     *            - used for JSON arrays validation when we need to check presence of some array items in result array.
     *            Use JsonCompareKeywords.ARRAY_CONTAINS.getKey() construction for that
     */
    public void validateResponse(JSONCompareMode mode, String... validationFlags) {
        validateResponse(mode, null, validationFlags);
    }

    /**
     * Validates JSON response using custom options
     *
     *  @param comparatorContext
     *            - stores additional validation items provided from outside
     * @param validationFlags
     *            - used for JSON arrays validation when we need to check presence of some array items in result array.
     *            Use JsonCompareKeywords.ARRAY_CONTAINS.getKey() construction for that
     */
    public void validateResponse(JsonComparatorContext comparatorContext, String... validationFlags) {
        validateResponse(JSONCompareMode.NON_EXTENSIBLE, comparatorContext, validationFlags);
    }

    /**
     * Validates JSON response using custom options
     * 
     * @param mode
     *            - determines how to compare 2 JSONs. See type description for more details. Mode is not applied for
     *            arrays comparison
     * @param comparatorContext
     *            - stores additional validation items provided from outside
     * @param validationFlags
     *            - used for JSON arrays validation when we need to check presence of some array items in result array.
     *            Use JsonCompareKeywords.ARRAY_CONTAINS.getKey() construction for that
     */
    public void validateResponse(JSONCompareMode mode, JsonComparatorContext comparatorContext, String... validationFlags) {
        if (rsPath == null) {
            throw new RuntimeException("Please specify rsPath to make Response body validation");
        }
        if (properties == null) {
            properties = new Properties();
        }
        if (actualRsBody == null) {
            throw new RuntimeException("Actual response body is null. Please make API call before validation response");
        }
        TemplateMessage tm = new TemplateMessage();
        tm.setIgnoredPropertiesProcessorClasses(ignoredPropertiesProcessorClasses);
        tm.setTemplatePath(rsPath);
        tm.setPropertiesStorage(properties);
        String expectedRs = tm.getMessageText();
        try {
            JSONAssert.assertEquals(expectedRs, actualRsBody, new JsonKeywordsComparator(actualRsBody, mode, comparatorContext, validationFlags));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Validates Xml response using custom options
     * 
     * @param mode - determines how to compare 2 XMLs. See {@link XmlCompareMode} for more details.
     */
    public void validateXmlResponse(XmlCompareMode mode) {
        if (actualRsBody == null) {
            throw new RuntimeException("Actual response body is null. Please make API call before validation response");
        }
        if (rsPath == null) {
            throw new RuntimeException("Please specify rsPath to make Response body validation");
        }
        XmlValidator.validateXml(actualRsBody, rsPath, mode);
    }

    /**
     * @param validationFlags parameter that specifies how to validate JSON response. Currently only array validation flag is supported.
     *                        Use JsonCompareKeywords.ARRAY_CONTAINS enum value for that
     */
    public void validateResponse(String... validationFlags) {
        switch (contentTypeEnum) {
        case JSON:
            validateResponse(JSONCompareMode.NON_EXTENSIBLE, validationFlags);
            break;
        case XML:
            validateXmlResponse(XmlCompareMode.STRICT);
            break;
        default:
            throw new RuntimeException("Unsupported argument of content type");
        }
    }

    /**
     * Validates actual API response per schema (JSON or XML depending on response body type).
     * Annotation {@link ContentType} on your AbstractApiMethodV2 class is used to determine whether to validate JSON or XML.
     * If ContentType is not specified then JSON schema validation will be applied by default.
     * 
     * @param schemaPath Path to schema file in resources
     */
    public void validateResponseAgainstSchema(String schemaPath) {
        if (actualRsBody == null) {
            throw new RuntimeException("Actual response body is null. Please make API call before validation response");
        }

        switch (contentTypeEnum) {
        case JSON:
            TemplateMessage tm = new TemplateMessage();
            tm.setIgnoredPropertiesProcessorClasses(ignoredPropertiesProcessorClasses);
            tm.setTemplatePath(schemaPath);
            String schema = tm.getMessageText();
            JsonValidator.validateJsonAgainstSchema(schema, actualRsBody);
            break;
        case XML:
            XmlValidator.validateXmlAgainstSchema(schemaPath, actualRsBody);
            break;
        default:
            throw new RuntimeException("Unsupported argument of content type: " + contentTypeEnum);
        }
    }

    public void setAuth(String jSessionId) {
        addCookie("pfJSESSIONID", jSessionId);
    }
}
