package com.stonewu.agenteam.service.edition;

import com.stonewu.agenteam.model.edition.entity.ProductEdition;

/** 由实际加载的发行组件说明安装包类型，不能通过网页参数或环境开关切换。 */
public interface EditionDescriptor {
    ProductEdition edition();
}
