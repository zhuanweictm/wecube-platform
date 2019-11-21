package com.webank.wecube.platform.core.service;

import com.webank.wecube.platform.core.commons.ApplicationProperties;
import com.webank.wecube.platform.core.commons.WecubeCoreException;
import com.webank.wecube.platform.core.dto.CommonResponseDto;
import com.webank.wecube.platform.core.model.datamodel.DataModelExpressionToRootData;
import com.webank.wecube.platform.core.parser.datamodel.DataModelExpressionParser;
import com.webank.wecube.platform.core.parser.datamodel.antlr4.DataModelParser;
import com.webank.wecube.platform.core.support.datamodel.DataModelExpressionDto;
import com.webank.wecube.platform.core.support.datamodel.TreeNode;
import com.webank.wecube.platform.core.utils.JsonUtils;
import com.webank.wecube.platform.core.utils.RestTemplateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataModelExpressionServiceImpl implements DataModelExpressionService {

    private static final String requestAllUrl = "http://{gatewayUrl}/{packageName}/entities/{entityName}";
    private static final Logger logger = LoggerFactory.getLogger(DataModelExpressionServiceImpl.class);


    @Autowired
    private RestTemplate restTemplate = new RestTemplate();
    @Autowired
    private ApplicationProperties applicationProperties;

    private static final String requestUrl = "http://{gatewayUrl}/{packageName}/entities/{entityName}?filter={attributeName},{value}";
    private static final String postRequestUrl = "http://{gatewayUrl}/{packageName}/entities/{entityName}/update";
    final String UNIQUE_IDENTIFIER = "id";
    private String requestActualUrl = "";
    private TreeNode treeNode;
    private List<TreeNode> anchorTreeNodeList = new ArrayList<>(); // stands for latest tree's most bottom leaves


    private String getRequestActualUrl() {
        return requestActualUrl;
    }

    private void setRequestActualUrl(String requestActualUrl) {
        this.requestActualUrl = requestActualUrl;
    }


    @Override
    public List<Object> fetchData(DataModelExpressionToRootData dataModelExpressionToRootData
    ) throws WecubeCoreException {

        Stack<DataModelExpressionDto> resultDtoStack = chainRequest(dataModelExpressionToRootData);

        return resultDtoStack.pop().getResultValue();
    }

    @Override
    public List<Object> targetEntityQuery(String packageName, String entityName) {
        Map<String, Object> getAllUrlParamMap = generateGetAllParamMap(this.applicationProperties.getGatewayUrl(), packageName, entityName);
        CommonResponseDto request = getRequest(requestAllUrl, getAllUrlParamMap);
        return commonResponseToList(request, DataModelExpressionParser.FETCH_ALL);
    }

    @Override
    public void writeBackData(DataModelExpressionToRootData expressionToRootData, Map<String, Object> writeBackData) throws WecubeCoreException {
        Stack<DataModelExpressionDto> resultDtoStack = chainRequest(expressionToRootData);
        List<CommonResponseDto> lastRequestResponse;
        DataModelExpressionDto finalFetchDto = Objects.requireNonNull(resultDtoStack.pop());
        String writeBackPackageName = null;
        String writeBackEntityName = null;
        if (resultDtoStack.empty()) {
            // no remain of stack, means the stack size is 1 when the function is invoked
            // {package}:{entity}.{attr} condition
            // the size of the stack is one
            lastRequestResponse = Objects.requireNonNull(finalFetchDto.getReturnedJson(), "No returned json found by the request.").pop();
            writeBackPackageName = Objects.requireNonNull(finalFetchDto.getEntity().pkg(), "Cannot find package.").getText();
            writeBackEntityName = Objects.requireNonNull(finalFetchDto.getEntity().ety(), "Cannot find entity.").getText();
        } else {
            DataModelExpressionDto lastLinkDto = resultDtoStack.pop();
            Stack<List<CommonResponseDto>> requestResponseList = lastLinkDto.getReturnedJson();
            lastRequestResponse = requestResponseList.pop();
            if (null != lastLinkDto.getOpFetch()) {
                // refBy
                writeBackPackageName = Objects.requireNonNull(lastLinkDto.getBwdNode().entity().pkg(), "Cannot find package.").getText();
                writeBackEntityName = Objects.requireNonNull(lastLinkDto.getBwdNode().entity().ety(), "Cannot find entity.").getText();

            }

            if (null != lastLinkDto.getOpTo()) {
                // refTo
                writeBackPackageName = Objects.requireNonNull(lastLinkDto.getEntity().pkg(), "Cannot find package.").getText();
                writeBackEntityName = Objects.requireNonNull(lastLinkDto.getEntity().ety(), "Cannot find attribute.").getText();
            }
        }
        String writeBackAttr = Objects.requireNonNull(finalFetchDto.getOpFetch()).attr().getText();
        Object writeBackId = commonResponseToList(lastRequestResponse.get(0), this.UNIQUE_IDENTIFIER).get(0);
        if (!writeBackData.containsKey(writeBackAttr)) {
            String msg = String.format("Cannot find attribute name [%s] from given write back data, " +
                    "check if expression's last fetch attribute is in your write back data.", writeBackAttr);
            logger.error(msg);
            throw new WecubeCoreException(msg);
        }
        Object writeBackValue = writeBackData.get(writeBackAttr);
        Map<String, Object> postRequestUrlParamMap = generatePostUrlParamMap(this.applicationProperties.getGatewayUrl(), writeBackPackageName, writeBackEntityName);
        List<Map<String, Object>> writeBackRequestBodyParamMap = generatePostBodyParamMap(writeBackId, writeBackAttr, writeBackValue);
        postRequest(postRequestUrl, postRequestUrlParamMap, writeBackRequestBodyParamMap);
    }

    @Override
    public List<TreeNode> getPreviewTree(DataModelExpressionToRootData expressionToRootData) {
        chainRequest(expressionToRootData);
        return this.flattenTreeNode(this.treeNode);
    }

    /**
     * Chain request operation from dataModelExpression and root Id data pair
     *
     * @param dataModelExpressionToRootData a support class comprises data model expression and root id data
     * @return request dto stack comprises returned value and intermediate responses, peek is the latest request
     */
    private Stack<DataModelExpressionDto> chainRequest(DataModelExpressionToRootData dataModelExpressionToRootData) {
        String dataModelExpression = dataModelExpressionToRootData.getDataModelExpression();
        String rootIdData = dataModelExpressionToRootData.getRootData();
        Stack<DataModelExpressionDto> resultDtoStack = new Stack<>();

        Queue<DataModelExpressionDto> expressionDtoQueue = new DataModelExpressionParser().parse(dataModelExpression);

        if (expressionDtoQueue.size() == 0) {
            String msg = String.format("Cannot extract information from the given expression [%s].", dataModelExpression);
            logger.error(msg);
            throw new WecubeCoreException(msg);
        }
        boolean isStart = true;
        DataModelExpressionDto lastExpressionDto = null;
        while (!expressionDtoQueue.isEmpty()) {
            DataModelExpressionDto expressionDto = expressionDtoQueue.poll();
            if (isStart) {
                resolveLink(expressionDto, rootIdData);
                isStart = false;
            } else {
                resolveLink(expressionDto, Objects.requireNonNull(lastExpressionDto));
            }
            if (!expressionDto.getReturnedJson().empty()) {
                lastExpressionDto = expressionDto;
            }
            resultDtoStack.add(expressionDto);
        }
        return resultDtoStack;
    }


    /**
     * Resolve first link which comprises only fwdNode, bwdNode and one {package}:{entity}.{attribute} situation.
     *
     * @param expressionDto first link expression dto
     * @param rootIdData    root data id data
     * @throws WecubeCoreException throw exception while request
     */
    private void resolveLink(DataModelExpressionDto expressionDto, String rootIdData) throws WecubeCoreException {
        // only invoke this condition when one "entity fetch" situation occurs
        if (expressionDto.getOpTo() == null && expressionDto.getOpBy() == null && expressionDto.getOpFetch() != null) {

            DataModelParser.EntityContext entity = expressionDto.getEntity();
            DataModelParser.FetchContext opFetch = expressionDto.getOpFetch();

            String requestPackageName = entity.pkg().getText();
            String requestEntityName = entity.ety().getText();

            // tree node
            this.treeNode = new TreeNode(requestPackageName, requestEntityName, rootIdData, null, null);

            // request
            Map<String, Object> requestParamMap = generateGetUrlParamMap(
                    this.applicationProperties.getGatewayUrl(),
                    requestPackageName,
                    requestEntityName,
                    "id",
                    rootIdData,
                    "id");

            CommonResponseDto requestResponseDto = getRequest(requestUrl, requestParamMap);
            expressionDto.getRequestUrlStack().add(Collections.singleton(requestActualUrl));
            expressionDto.getReturnedJson().add(Collections.singletonList(requestResponseDto));

            String fetchAttributeName = opFetch.attr().getText();
            List<Object> finalResult = commonResponseToList(requestResponseDto, fetchAttributeName);

            expressionDto.setResultValue(finalResult);
        }

        // only invoke this function when the first link with rootIdData is processed
        // no need to process prev_link
        if (expressionDto.getOpTo() != null) {
            // refTo
            DataModelParser.Fwd_nodeContext fwdNode = expressionDto.getFwdNode();
            DataModelParser.EntityContext entity = expressionDto.getEntity();
            String firstRequestPackageName = fwdNode.entity().pkg().getText();
            String firstRequestEntityName = fwdNode.entity().ety().getText();

            // first tree node
            this.treeNode = new TreeNode(firstRequestPackageName, firstRequestEntityName, rootIdData, null, new ArrayList<>());

            // first request
            Map<String, Object> firstRequestParamMap = generateGetUrlParamMap(
                    this.applicationProperties.getGatewayUrl(),
                    firstRequestPackageName,
                    firstRequestEntityName,
                    "id",
                    rootIdData,
                    "id");
            CommonResponseDto firstRequestResponseDto = getRequest(requestUrl, firstRequestParamMap);
            expressionDto.getRequestUrlStack().add(Collections.singleton(requestActualUrl));
            expressionDto.getReturnedJson().add(Collections.singletonList(firstRequestResponseDto));

            // second request
            // fwdNode returned data is the second request's id data
            String secondRequestPackageName = entity.pkg().getText();
            String secondRequestEntityName = entity.ety().getText();
            String secondRequestAttrName = fwdNode.attr().getText();
            List<Object> secondRequestIdDataList = commonResponseToList(firstRequestResponseDto, secondRequestAttrName);
            List<CommonResponseDto> responseDtoList = new ArrayList<>();
            for (Object secondRequestIdData : secondRequestIdDataList) {
                Map<String, Object> secondRequestParamMap = generateGetUrlParamMap(
                        this.applicationProperties.getGatewayUrl(),
                        secondRequestPackageName,
                        secondRequestEntityName,
                        "id",
                        secondRequestIdData,
                        "id");
                CommonResponseDto secondRequestResponse = getRequest(requestUrl, secondRequestParamMap);
                responseDtoList.add(secondRequestResponse);

                // set child tree node and update parent tree node
                TreeNode childNode = new TreeNode(secondRequestPackageName, secondRequestEntityName, secondRequestIdData, this.treeNode, new ArrayList<>());
                this.treeNode.getChildren().add(childNode);
                this.anchorTreeNodeList.add(childNode);
            }
            expressionDto.getRequestUrlStack().add(Collections.singleton(requestActualUrl));
            expressionDto.getReturnedJson().add(responseDtoList);
        }

        if (expressionDto.getOpBy() != null) {
            // refBy
            DataModelParser.EntityContext entity = expressionDto.getEntity();
            DataModelParser.Bwd_nodeContext bwdNode = expressionDto.getBwdNode();
            String requestPackageName = bwdNode.entity().pkg().getText();
            String requestEntityName = bwdNode.entity().ety().getText();
            String requestAttributeName = bwdNode.attr().getText();

            // first TreeNode, which is the entity
            this.treeNode = new TreeNode(entity.pkg().getText(), entity.ety().getText(), rootIdData, null, new ArrayList<>());

            // refBy request
            Map<String, Object> requestParamMap = generateGetUrlParamMap(
                    this.applicationProperties.getGatewayUrl(),
                    requestPackageName,
                    requestEntityName,
                    requestAttributeName,
                    rootIdData,
                    "id");
            CommonResponseDto secondRequestResponse = getRequest(requestUrl, requestParamMap);  // this response may have data with one or multiple lines.
            // second TreeNode, might be multiple
            List<Object> refByDataIdList = commonResponseToList(secondRequestResponse, "id");
            refByDataIdList.forEach(id -> {
                TreeNode childNode = new TreeNode(requestPackageName, requestEntityName, id, this.treeNode, new ArrayList<>());
                this.treeNode.getChildren().add(childNode);
                this.anchorTreeNodeList.add(childNode);
            });
            expressionDto.getRequestUrlStack().add(Collections.singleton(requestActualUrl));
            expressionDto.getReturnedJson().add(Collections.singletonList(secondRequestResponse));

        }
    }

    /**
     * Resolve links which comprise previous links and final fetch action
     *
     * @param expressionDto     subsequent link expression dto
     * @param lastExpressionDto the expression dto from last link
     * @throws WecubeCoreException throw exception through the request
     */
    private void resolveLink(DataModelExpressionDto expressionDto, DataModelExpressionDto lastExpressionDto) throws WecubeCoreException {
        List<CommonResponseDto> lastRequestResultList = lastExpressionDto.getReturnedJson().peek();
        List<TreeNode> newAnchorTreeNodeList = new ArrayList<>();

        // last request info
        String lastRequestPackageName;
        String lastRequestEntityName;
        if (lastExpressionDto.getOpTo() != null) {
            // the last expression is refTo
            lastRequestPackageName = Objects.requireNonNull(lastExpressionDto.getEntity().pkg()).getText();
            lastRequestEntityName = Objects.requireNonNull(lastExpressionDto.getEntity().ety()).getText();
        } else {
            // the last expression is refBy
            lastRequestPackageName = Objects.requireNonNull(lastExpressionDto.getBwdNode().entity().pkg()).getText();
            lastRequestEntityName = Objects.requireNonNull(lastExpressionDto.getBwdNode().entity().ety()).getText();
        }

        if (expressionDto.getOpTo() != null) {
            // refTo

            // new request info
            String requestId = expressionDto.getOpFetch().attr().getText();
            String requestPackageName = expressionDto.getEntity().pkg().getText();
            String requestEntityName = expressionDto.getEntity().ety().getText();

            List<CommonResponseDto> responseDtoList = new ArrayList<>();
            Set<String> requestUrlSet = new HashSet<>();
            for (CommonResponseDto lastRequestResponseDto : lastRequestResultList) {


                // request for data and update the parent tree node
                List<Object> requestIdDataList = commonResponseToList(lastRequestResponseDto, requestId);
                for (Object requestIdData : requestIdDataList) {
                    // find parent tree node, from attribute to id might found multiple ID which means multiple tree nodes
                    List<Object> parentIdList = getResponseIdFromAttribute(lastRequestResponseDto, requestId, requestIdData);
                    List<TreeNode> parentTreeNodeList = new ArrayList<>();
                    Objects.requireNonNull(parentIdList).forEach(id -> {
                        TreeNode parentNode = findParentNode(this.anchorTreeNodeList, lastRequestPackageName, lastRequestEntityName, id);
                        Objects.requireNonNull(parentNode, "Cannot find parent node from given last request info");
                        parentTreeNodeList.add(parentNode);
                    });

                    Map<String, Object> requestParamMap = generateGetUrlParamMap(
                            this.applicationProperties.getGatewayUrl(),
                            requestPackageName,
                            requestEntityName,
                            "id",
                            requestIdData,
                            "id");
                    CommonResponseDto requestResponse = getRequest(requestUrl, requestParamMap);
                    requestUrlSet.add(requestActualUrl);
                    responseDtoList.add(requestResponse);

                    // set child tree node and update parent tree node
                    List<Object> responseIdList = commonResponseToList(requestResponse, "id");
                    responseIdList.forEach(id -> {
                        // the list's size is one due to it's referenceTo operation
                        parentTreeNodeList.forEach(parentNode -> {
                            // bind childNode which is generated by one id to multiple parents
                            TreeNode childNode = new TreeNode(requestPackageName, requestEntityName, id, parentNode, new ArrayList<>());
                            parentNode.getChildren().add(childNode);
                            newAnchorTreeNodeList.add(childNode);
                        });
                    });
                }
            }
            expressionDto.getRequestUrlStack().add(requestUrlSet);
            expressionDto.getReturnedJson().add(responseDtoList);
        }

        if (expressionDto.getOpBy() != null) {
            // refBy

            // new request info
            DataModelParser.Bwd_nodeContext bwdNode = expressionDto.getBwdNode();
            String requestPackageName = bwdNode.entity().pkg().getText();
            String requestEntityName = bwdNode.entity().ety().getText();
            String requestAttributeName = bwdNode.attr().getText();

            List<CommonResponseDto> responseDtoList = new ArrayList<>();
            Set<String> requestUrlSet = new HashSet<>();
            for (CommonResponseDto lastRequestResponseDto : lastRequestResultList) {

                List<Object> requestIdDataList = commonResponseToList(lastRequestResponseDto, "id");
                for (Object requestIdData : requestIdDataList) {
                    Objects.requireNonNull(requestIdData,
                            "Cannot find 'id' from last request response. " +
                                    "Please ensure that the interface returned the data with one key named: 'id' as the development guideline requires.");
                    // find parent tree node
                    TreeNode parentNode = findParentNode(this.anchorTreeNodeList, lastRequestPackageName, lastRequestEntityName, requestIdData);
                    Objects.requireNonNull(parentNode, "Cannot find parent node from given last request info");

                    Map<String, Object> requestParamMap = generateGetUrlParamMap(
                            this.applicationProperties.getGatewayUrl(),
                            requestPackageName,
                            requestEntityName,
                            requestAttributeName,
                            requestIdData,
                            "id");
                    CommonResponseDto requestResponse = getRequest(requestUrl, requestParamMap);
                    requestUrlSet.add(requestActualUrl);
                    responseDtoList.add(requestResponse);

                    // set child tree node and update parent tree node
                    List<Object> responseIdList = commonResponseToList(requestResponse, "id");
                    responseIdList.forEach(id -> {
                        TreeNode childNode = new TreeNode(requestPackageName, requestEntityName, id, parentNode, new ArrayList<>());
                        parentNode.getChildren().add(childNode);
                        newAnchorTreeNodeList.add(childNode);
                    });
                }
            }
            expressionDto.getRequestUrlStack().add(requestUrlSet);
            expressionDto.getReturnedJson().add(responseDtoList);
        }

        if (expressionDto.getOpBy() == null && expressionDto.getOpTo() == null && expressionDto.getOpFetch() != null) {
            // final route, which is prev link and fetch
            String attrName = expressionDto.getOpFetch().attr().getText();
            List<Object> resultValueList = new ArrayList<>();
            for (CommonResponseDto lastRequestResult : lastRequestResultList) {
                List<Object> fetchDataList = commonResponseToList(lastRequestResult, attrName);
                resultValueList.addAll(fetchDataList);
            }
            expressionDto.setResultValue(resultValueList);
        }

        // update anchor tree node list
        this.anchorTreeNodeList = newAnchorTreeNodeList;
    }

    private List<Object> getResponseIdFromAttribute(CommonResponseDto lastRequestResponseDto, String requestAttributeName, Object requestAttributeValue) throws WecubeCoreException {
        List<Object> result = new ArrayList<>();
        List<Object> requestResponseDataList = commonResponseToList(lastRequestResponseDto, DataModelExpressionParser.FETCH_ALL);
        requestResponseDataList.forEach(o -> {
            if (!LinkedHashMap.class.getSimpleName().equals(o.getClass().getSimpleName())) {
                String msg = "Cannot transfer lastRequestResponse list to LinkedHashMap.";
                logger.error(msg, lastRequestResponseDto, requestAttributeName, requestAttributeValue);
                throw new WecubeCoreException(msg);
            }
            LinkedHashMap<String, Object> requestResponseDataMap = (LinkedHashMap<String, Object>) o;
            if (requestAttributeValue.equals(requestResponseDataMap.get(requestAttributeName))) {
                result.add(requestResponseDataMap.get("id"));
            }
        });

        return result;
    }

    /**
     * Find parent tree node from last link's operation
     *
     * @param anchorTreeNodeList     intermediate tree node list as anchor, which is the latest tree's most bottom leaves
     * @param lastRequestPackageName last request package name
     * @param lastRequestEntityName  last request entity name
     * @param rootIdData             tree node's root id data
     * @return found tree node or null
     */
    private TreeNode findParentNode(List<TreeNode> anchorTreeNodeList, String lastRequestPackageName, String lastRequestEntityName, Object rootIdData) {
        for (TreeNode node : anchorTreeNodeList) {
            if (node.equals(new TreeNode(lastRequestPackageName, lastRequestEntityName, rootIdData))) return node;
        }
        return null;
    }

    /**
     * Generation of fetch data url param map
     *
     * @param gatewayUrl    gate way url
     * @param packageName   package name
     * @param entityName    entity name
     * @param attributeName attribute name
     * @param value         value
     * @param sortName      sort name
     * @return response map
     */
    private Map<String, Object> generateGetUrlParamMap(Object gatewayUrl,
                                                       Object packageName,
                                                       Object entityName,
                                                       Object attributeName,
                                                       Object value,
                                                       Object sortName) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("gatewayUrl", gatewayUrl);
        paramMap.put("packageName", packageName);
        paramMap.put("entityName", entityName);
        paramMap.put("attributeName", attributeName);
        paramMap.put("value", value);
//        paramMap.add("sortName", sortName);
        return paramMap;
    }

    /**
     * Generation of fetch all entity data url param map
     *
     * @param gatewayUrl  gate way url
     * @param packageName package name
     * @param entityName  entity name
     * @return response map
     */
    private Map<String, Object> generateGetAllParamMap(Object gatewayUrl,
                                                       Object packageName,
                                                       Object entityName) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("gatewayUrl", gatewayUrl);
        paramMap.put("packageName", packageName);
        paramMap.put("entityName", entityName);
        return paramMap;
    }

    /**
     * Generation of data write back url param map
     *
     * @param gatewayUrl  gateway url
     * @param packageName package name
     * @param entityName  entity name
     * @return generated param map for url binding
     */
    private Map<String, Object> generatePostUrlParamMap(Object gatewayUrl,
                                                        Object packageName,
                                                        Object entityName) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("gatewayUrl", gatewayUrl);
        paramMap.put("packageName", packageName);
        paramMap.put("entityName", entityName);
        return paramMap;

    }

    /**
     * Generation of data write back body param map
     *
     * @param entityId       gateway url
     * @param attributeName  package name
     * @param attributeValue entity name
     * @return generated param map for url binding
     */
    private List<Map<String, Object>> generatePostBodyParamMap(Object entityId,
                                                               Object attributeName,
                                                               Object attributeValue) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("id", entityId);
        paramMap.put("attr_name", attributeName);
        paramMap.put("attr_value", attributeValue);
        return Collections.singletonList(paramMap);

    }

    /**
     * Issue a request from request url with place holders and param map
     *
     * @param requestUrl request url with place holders
     * @param paramMap   generated param map
     * @return common response dto
     * @throws WecubeCoreException catch exception during sending the request
     */
    private CommonResponseDto getRequest(String requestUrl, Map<String, Object> paramMap) throws WecubeCoreException {
        ResponseEntity<String> response;
        CommonResponseDto responseDto;
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            // combine url with param map
            UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromUriString(requestUrl);
            UriComponents uriComponents = uriComponentsBuilder.buildAndExpand(paramMap);
            String uriStr = uriComponents.toString();
            if (!this.getRequestActualUrl().equals(uriStr))
                this.setRequestActualUrl(uriStr);
            response = RestTemplateUtils.sendGetRequestWithParamMap(restTemplate, uriStr, httpHeaders);
            if (StringUtils.isEmpty(response.getBody()) || response.getStatusCode().isError()) {
                throw new WecubeCoreException(response.toString());
            }
            responseDto = JsonUtils.toObject(response.getBody(), CommonResponseDto.class);
            if (!CommonResponseDto.STATUS_OK.equals(responseDto.getStatus())) {
                String msg = String.format("Request error! The error message is [%s]", responseDto.getMessage());
                logger.error(msg);
                throw new WecubeCoreException(msg);
            }
        } catch (IOException ex) {
            logger.error(ex.getMessage());
            throw new WecubeCoreException(ex.getMessage());
        }

        return responseDto;
    }

    /**
     * Issue a request from request url with place holders and param map
     *
     * @param requestUrl request url with place holders
     * @param paramMap   generated param map
     * @throws WecubeCoreException catch exception during sending the request
     */
    private void postRequest(String requestUrl, Map<String, Object> paramMap, List<Map<String, Object>> requestBodyParamMap) throws WecubeCoreException {
        ResponseEntity<String> response;
        CommonResponseDto responseDto;
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            // combine url with param map
            UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromUriString(requestUrl);
            UriComponents uriComponents = uriComponentsBuilder.buildAndExpand(paramMap);
            String uriStr = uriComponents.toString();
            if (!this.getRequestActualUrl().equals(uriStr))
                this.setRequestActualUrl(uriStr);
            response = RestTemplateUtils.sendPostRequestWithParamMap(restTemplate, uriStr, requestBodyParamMap, httpHeaders);
            if (StringUtils.isEmpty(response.getBody()) || response.getStatusCode().isError()) {
                String msg = String.format("Error when sending post request to target server, the response is: [%s]", response.toString());
                throw new WecubeCoreException(msg);
            }
            responseDto = JsonUtils.toObject(response.getBody(), CommonResponseDto.class);
            if (!CommonResponseDto.STATUS_OK.equals(responseDto.getStatus())) {
                String msg = String.format("Request error! The error message is [%s]", responseDto.getMessage());
                logger.error(msg);
                throw new WecubeCoreException(msg);
            }
        } catch (IOException ex) {
            logger.error(ex.getMessage());
            throw new WecubeCoreException(ex.getMessage());
        }

    }

    /**
     * Handle response and resolve it to list of objects
     *
     * @param responseDto   common response dto
     * @param attributeName the attribute name the expression want to fetch
     * @return list of value fetched from expression
     */
    private List<Object> commonResponseToList(CommonResponseDto responseDto, String attributeName) {
        // transfer dto to List<LinkedTreeMap>
        List<LinkedHashMap<String, Object>> dataArray = new ArrayList<>();
        List<Object> returnList;
        String dataTypeSimpleName = responseDto.getData().getClass().getSimpleName();

        if (ArrayList.class.getSimpleName().equals(dataTypeSimpleName)) {
            dataArray = (List<LinkedHashMap<String, Object>>) responseDto.getData();
        }

        if (LinkedHashMap.class.getSimpleName().equals(dataTypeSimpleName)) {
            dataArray.add((LinkedHashMap) responseDto.getData());
        }

        if (DataModelExpressionParser.FETCH_ALL.equals(attributeName)) {
            returnList = Objects.requireNonNull(dataArray)
                    .stream()
                    .sorted(Comparator.comparing(o -> String.valueOf(o.get("id"))))
                    .collect(Collectors.toList());
        } else {
            returnList = Objects.requireNonNull(dataArray)
                    .stream()
                    .sorted(Comparator.comparing(o -> String.valueOf(o.get("id"))))
                    .map(linkedTreeMap -> linkedTreeMap.get(attributeName))
                    .collect(Collectors.toList());
        }

        return returnList;
    }

    private List<TreeNode> flattenTreeNode(TreeNode treeNode) {
        List<TreeNode> result = new ArrayList<>();
        if (null != treeNode.getChildren() && !treeNode.getChildren().isEmpty()) {
            Objects.requireNonNull(treeNode.getChildren()).forEach(childNode -> {
                result.addAll(flattenTreeNode(childNode));
            });
        }
        result.add(treeNode);
        return result;
    }
}
