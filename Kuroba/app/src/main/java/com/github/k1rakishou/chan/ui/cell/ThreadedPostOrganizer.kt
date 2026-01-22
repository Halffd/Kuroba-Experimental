package com.github.k1rakishou.chan.ui.cell

import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.post.ConversationTree
import com.github.k1rakishou.model.data.post.PostGraph
import com.github.k1rakishou.model.data.post.ReplyInfo
import com.github.k1rakishou.model.data.descriptor.PostDescriptor

/**
 * Handles the conversion between flat post lists and threaded post lists based on user settings
 */
class ThreadedPostOrganizer {

  /**
   * Organizes posts based on user settings - either as a flat list or as a threaded conversation tree
   */
  fun organizePosts(posts: List<ChanPost>): List<ChanPostWithLevel> {
    if (!ChanSettings.threadedRepliesEnabled.get()) {
      // Return posts as a flat list if threading is disabled
      return posts.map { post -> ChanPostWithLevel(post, 0) }
    }

    // Build the conversation tree
    val postGraph = PostGraph()
    postGraph.setDebug(false) // Set to true for debugging
    postGraph.buildFromPosts(posts)

    // For a complete thread, we need to get all conversation trees starting from OP posts
    val opPosts = posts.filter { post -> post.isOP() }

    if (opPosts.isNotEmpty()) {
      // Start from each OP post and get the conversation tree
      val allReplyInfos = mutableListOf<ReplyInfo>()

      for (opPost in opPosts) {
        val replyInfos = postGraph.getFullConversationTree(opPost.postDescriptor)
        allReplyInfos.addAll(replyInfos)
      }

      // Remove duplicates while preserving order
      val uniqueReplyInfos = allReplyInfos.distinctBy { it.postDescriptor }

      val maxDepth = ChanSettings.threadedRepliesMaxDepth.get()

      // Convert to posts with levels, respecting max depth
      return uniqueReplyInfos.map { replyInfo ->
        ChanPostWithLevel(
          post = replyInfo.data,
          level = minOf(replyInfo.level, maxDepth)
        )
      }
    } else {
      // If no OP posts found, just return the original posts with level 0
      return posts.map { post -> ChanPostWithLevel(post, 0) }
    }
  }

  /**
   * Creates a conversation tree for a specific post
   */
  fun createConversationTreeForPost(posts: List<ChanPost>, targetPostDescriptor: PostDescriptor): List<ReplyInfo> {
    val postGraph = PostGraph()
    postGraph.setDebug(false)
    postGraph.buildFromPosts(posts)

    return postGraph.getReplyChainFromPost(targetPostDescriptor)
  }
}

/**
 * Wrapper class that holds a post along with its nesting level for threaded replies
 */
data class ChanPostWithLevel(
  val post: ChanPost,
  val level: Int
)