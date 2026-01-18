package com.example.demo.interfaces;

public interface BotCommandsRepository {
    String CMD_STATUS = "/status";
    String CMD_CLOSE = "/close";
    String CMD_CLOSE_ALL = "/closeall";
    String CMD_CLEAR_HISTORY = "/clearhistory";

    String ACTION_CONFIRM = "confirm_close";
    String ACTION_EXECUTE = "execute_close";
    String ACTION_EXECUTE_ALL = "execute_close_all";
    String ACTION_CANCEL = "cancel";
}
