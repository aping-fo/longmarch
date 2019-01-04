package com.game.service;

import com.game.sdk.net.Result;
import com.game.sdk.utils.ErrorCode;
import com.google.common.collect.Lists;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BroadcastService extends AbstractService {
    private final int CONTENT_SIZE = 30;
    private final String BUYEVENT_CONTENT_FORMAT = "%s兑换了%s";
    private final String WINEVENT_CONTENT_FORMAT = "%s赢取%d健康币";
    private List<String> lists = Lists.newArrayListWithCapacity(CONTENT_SIZE);

    public Result getBroadcast(){
        if(lists == null){
            return Result.valueOf(ErrorCode.SERVER_INTERNAL_ERROR, "error");
        }

        return Result.valueOf(ErrorCode.OK, lists);
    }

    public void addBuyEvent(String name, String itemName){
        this.addEvent(String.format(BUYEVENT_CONTENT_FORMAT, name, itemName));
    }

    public void addWinEvent(String name, int winScore){
        this.addEvent(String.format(WINEVENT_CONTENT_FORMAT, name, winScore));
    }

    private void addEvent(String content){
        lists.add(0, content);
        if (lists.size() > CONTENT_SIZE) {
            lists.remove(CONTENT_SIZE);
        }
        lists.add(content);
    }
}