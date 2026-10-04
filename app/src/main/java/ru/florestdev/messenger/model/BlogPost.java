package ru.florestdev.messenger.model;

import java.util.ArrayList;
import java.util.List;

public class BlogPost {
    public int id;
    public String author;
    public String title;
    public String content;
    public String createdAt;
    public int likesCount;
    public boolean liked;
    public List<Comment> comments = new ArrayList<>();

    public static class Comment {
        public String username;
        public String content;
        public String createdAt;
    }
}
