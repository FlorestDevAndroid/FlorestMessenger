package ru.florestdev.messenger.model;

import java.util.ArrayList;
import java.util.List;

public class Organization {
    public int id;
    public String name;
    public String owner;
    public String inviteSlug;
    public String description;
    public String role;
    public List<Chat> chats = new ArrayList<>();
    public List<Member> members = new ArrayList<>();

    public static class Chat {
        public int id;
        public String name;
    }
    public static class Member {
        public String username;
        public String role;
    }
}
