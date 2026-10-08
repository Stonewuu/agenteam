package com.stonewu.agenteam.controller.data;

import com.stonewu.agenteam.model.data.request.DataCollectionWriteRequest;
import com.stonewu.agenteam.model.data.response.DataCollectionView;
import com.stonewu.agenteam.model.data.response.DataQueryResultView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.data.DataApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 数据集合读取、字段修改和结构化只读查询入口。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/data/{resourceId}")
public class DataApiController {

    private final DataApiService data;

    private final ApiResponses responses;

    public DataApiController(DataApiService data, ApiResponses responses) {
        this.data = data;
        this.responses = responses;
    }

    @GetMapping("/collections")
    public ApiResponse<PageResponse<DataCollectionView>> list(@PathVariable String enterpriseId,
                                                              @PathVariable String resourceId,
                                                              @RequestParam(required = false) Integer limit,
                                                              @RequestParam(required = false) String cursor,
                                                              HttpServletRequest request) {
        return responses.success(data.list(enterpriseId, resourceId, limit, cursor, request), request);
    }

    @PutMapping("/collections/{collectionId}")
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId,
                                                      @PathVariable String collectionId,
                                                      @RequestBody DataCollectionWriteRequest input,
                                                      HttpServletRequest request) {
        return responses.operation(data.update(enterpriseId, resourceId, collectionId, input, request), request);
    }

    @PostMapping("/collections")
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId,
                                                      @RequestBody DataCollectionWriteRequest input,
                                                      HttpServletRequest request) {
        return responses.operation(data.create(enterpriseId, resourceId, input, request), request);
    }

    @PostMapping("/check")
    public ResponseEntity<ApiResponse<Object>> check(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                     HttpServletRequest request) {
        return responses.operation(data.check(enterpriseId, resourceId, request), request);
    }

    @PostMapping("/query")
    public ApiResponse<DataQueryResultView> query(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                  HttpServletRequest request) {
        return responses.success(data.query(enterpriseId, resourceId, request), request);
    }
}
