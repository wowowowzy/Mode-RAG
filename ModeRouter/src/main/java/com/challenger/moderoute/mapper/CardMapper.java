package com.challenger.moderoute.mapper;

import com.challenger.moderoute.entity.Card;

import java.util.List;
import java.util.Optional;

/**
 * 模式卡数据访问。运行时唯一读取入口是数据库；
 * YAML 只作为导入种子（编辑仍在文件上做，方便 diff 与评审）。
 */
public interface CardMapper {

    /** 当前库里已导入的库版本，未导入时为空。 */
    Optional<String> loadedVersion();

    /** 整批替换：一次事务内清空旧卡并写入新快照。 */
    void replaceAll(String libraryVersion, List<Card> cards);

    /** 按 ordinal 升序读取全部卡片。 */
    List<Card> loadAll();
}
