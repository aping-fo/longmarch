package com.game.dao;

import com.game.domain.group.Group;
import net.paoding.rose.jade.annotation.DAO;
import net.paoding.rose.jade.annotation.SQL;
import net.paoding.rose.jade.annotation.SQLParam;

import java.util.List;

@DAO
public interface GroupDAO {

    @SQL("select * from t_u_family where id = :id")
    public Group queryGroup(@SQLParam("id") String id);

    @SQL("REPLACE INTO t_u_family VALUES (:group.id,:group.memberJson,:group.ownerId,:group.iconUrl,:group.name,:group.totalStep,:group.recordJson)")
    public void saveOrUpdate(@SQLParam("group") Group group);

    @SQL("select count(1) from t_u_player")
    public Integer selectPlayerCount();

    @SQL("SELECT id, totalStep, name FROM t_u_family ORDER BY totalStep DESC LIMIT 50")
    public List<Group> queryGroupRank();

    @SQL("SELECT * FROM t_u_family")
    public List<Group> queryGroup();

    @SQL("delete FROM t_u_family where id = :id")
    public List<Group> deleteGroup(@SQLParam("id") String id);

}
