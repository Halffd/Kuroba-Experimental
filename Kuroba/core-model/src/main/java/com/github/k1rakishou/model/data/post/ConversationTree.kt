package com.github.k1rakishou.model.data.post

import com.github.k1rakishou.common.mapNotNullToSet
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.core_logger.Logger

/**
 * Represents a conversation tree structure that organizes posts based on quote relationships
 */
class ConversationTree(
  private val postGraph: PostGraph
) {
  private val roots = mutableMapOf<PostDescriptor, Reply>()
  private val allReplies = mutableMapOf<PostDescriptor, Reply>()
  private var debugEnabled = false

  fun log(message: String) {
    if (debugEnabled) {
      Logger.d("ConversationTree", message)
    }
  }

  fun buildFromPostGraph(
    startPostDescriptor: PostDescriptor,
    mode: Mode = Mode.AROUND,
    includeOp: Boolean = true
  ): ConversationTree {
    log("Building tree from PostGraph for $startPostDescriptor (${mode.name})")

    roots.clear()
    allReplies.clear()

    val relevantPosts = collectRelevantPosts(startPostDescriptor, mode, includeOp)

    // Create Reply objects
    relevantPosts.forEach { postDescriptor ->
      val node = postGraph.nodes[postDescriptor]
      if (node != null) {
        val reply = Reply(
          postDescriptor = postDescriptor,
          data = node.data,
          level = 0
        )
        
        val commentText = node.data.postComment.originalComment().toString()
        reply.quotedParents.addAll(extractParentIds(commentText, postDescriptor).filter { pid ->
          relevantPosts.contains(pid)
        })
        
        reply.isOP = postGraph.findOP(postDescriptor) == postDescriptor
        allReplies[postDescriptor] = reply
      }
    }

    // Build the tree using quote relationships
    buildSimpleTree()

    return this
  }

  private fun attachToParent(reply: Reply, processed: Set<PostDescriptor>, processing: MutableSet<PostDescriptor>): Boolean {
    processing.add(reply.postDescriptor)

    var bestParent: Reply? = null
    var bestScore = -1L

    for (quotedId in reply.quotedParents) {
      if (!allReplies.containsKey(quotedId)) continue
      if (!processed.contains(quotedId)) {
        processing.remove(reply.postDescriptor)
        return false
      }
      if (processing.contains(quotedId)) {
        log("Cycle detected: ${reply.postDescriptor} -> $quotedId")
        continue
      }

      val parentReply = allReplies[quotedId] ?: continue

      // Score parents by chronological order AND tree depth
      val chronologicalScore = quotedId.postNo
      val depthPenalty = parentReply.level * 100L // Small penalty for deeper nesting
      val score = chronologicalScore - depthPenalty

      if (score > bestScore) {
        bestScore = score
        bestParent = parentReply
      }
    }

    processing.remove(reply.postDescriptor)

    if (bestParent != null) {
      bestParent.addChild(reply)
      log("Multi-quote ${reply.postDescriptor} attached to latest parent ${bestParent.postDescriptor} (score: $bestScore)")
      return true
    }

    return false
  }

  private fun buildSimpleTree() {
    log("Building simple tree structure")

    val processed = mutableSetOf<PostDescriptor>()

    // Step 1: Find all root posts
    for ((postDescriptor, reply) in allReplies) {
      if (reply.isOP || reply.quotedParents.isEmpty()) {
        roots[postDescriptor] = reply
        reply.level = 0
        processed.add(postDescriptor)
        log("Root found: $postDescriptor (isOP: ${reply.isOP})")
      }
    }

    // Step 2: Build the tree in multiple passes
    val maxPasses = 10
    var pass = 0
    var changed = true

    while (changed && pass < maxPasses) {
      pass++
      changed = false
      val processing = mutableSetOf<PostDescriptor>()
      log("=== PASS $pass ===")

      for ((postDescriptor, reply) in allReplies) {
        if (processed.contains(postDescriptor)) continue // Skip already processed

        val success = attachToParent(reply, processed, processing)
        if (success) {
          processed.add(postDescriptor)
          changed = true
          log("Attached $postDescriptor to ${reply.parent?.postDescriptor} at level ${reply.level}")
        }
      }
    }

    // Step 3: Remaining posts become roots
    for ((postDescriptor, reply) in allReplies) {
      if (!processed.contains(postDescriptor)) {
        roots[postDescriptor] = reply
        reply.level = 0
        log("Orphan became root: $postDescriptor")
      }
    }

    log("Final tree: ${roots.size} roots, ${allReplies.size} total posts")
  }

  private fun collectRelevantPosts(
    startPostDescriptor: PostDescriptor,
    mode: Mode,
    includeOp: Boolean = true
  ): Set<PostDescriptor> {
    val relevant = mutableSetOf<PostDescriptor>()
    val visited = mutableSetOf<PostDescriptor>()

    log("Collecting posts for $startPostDescriptor in ${mode.name} mode")

    when (mode) {
      Mode.AROUND -> {
        // FULL MODE: Follow conversation thread with OP safety
        val queue = mutableListOf(startPostDescriptor)
        val maxPosts = 300000
        var collected = 0

        while (queue.isNotEmpty() && collected < maxPosts) {
          val currentId = queue.removeAt(0)
          if (visited.contains(currentId)) continue

          visited.add(currentId)
          relevant.add(currentId)
          collected++

          val node = postGraph.nodes[currentId]
          if (node == null) {
            continue
          }

          // Add OP but with EXTREME caution
          val opId = postGraph.findOP(currentId)
          if (opId != null && !relevant.contains(opId) && collected < maxPosts) {
            relevant.add(opId)
            collected++

            // CRITICAL: DO NOT add OP replies in full mode unless they're direct ancestors
            // Only add the path from OP to our conversation
            if (currentId != opId) {
              val pathFromOp = getPathFromOpToPost(opId, currentId)
              pathFromOp.forEach { pathPostId ->
                if (!relevant.contains(pathPostId) && collected < maxPosts) {
                  relevant.add(pathPostId)
                  collected++
                }
              }
            }
          }

          // Add quoted posts (backward links)
          val commentText = node.data.postComment.originalComment().toString()
          val quotedParents = extractParentIds(commentText, currentId)
          quotedParents.forEach { pid ->
            if (postGraph.nodes.containsKey(pid) && !visited.contains(pid) && collected < maxPosts) {
              queue.add(pid)
            }
          }

          // Add replies - but SKIP if current post is OP
          if (currentId != opId || startPostDescriptor == opId) {
            node.replies.forEach { replyId ->
              if (!visited.contains(replyId) && collected < maxPosts) {
                queue.add(replyId)
              }
            }
          } else {
            // If this IS the OP, only add replies that are directly quoted by posts in our set
            log("At OP $opId - skipping bulk reply addition")
          }
        }
      }
      Mode.FULL -> {
        // FULL MODE: Follow conversation thread with OP safety
        val queue = mutableListOf(startPostDescriptor)
        val maxPosts = 300000
        var collected = 0

        while (queue.isNotEmpty() && collected < maxPosts) {
          val currentId = queue.removeAt(0)
          if (visited.contains(currentId)) continue

          visited.add(currentId)
          relevant.add(currentId)
          collected++

          val node = postGraph.nodes[currentId]
          if (node == null) {
            continue
          }

          // Add OP but with EXTREME caution
          val opId = postGraph.findOP(currentId)
          if (opId != null && !relevant.contains(opId) && collected < maxPosts) {
            relevant.add(opId)
            collected++

            // CRITICAL: DO NOT add OP replies in full mode unless they're direct ancestors
            // Only add the path from OP to our conversation
            if (currentId != opId) {
              val pathFromOp = getPathFromOpToPost(opId, currentId)
              pathFromOp.forEach { pathPostId ->
                if (!relevant.contains(pathPostId) && collected < maxPosts) {
                  relevant.add(pathPostId)
                  collected++
                }
              }
            }
          }

          // Add quoted posts (backward links)
          val commentText = node.data.postComment.originalComment().toString()
          val quotedParents = extractParentIds(commentText, currentId)
          quotedParents.forEach { pid ->
            if (postGraph.nodes.containsKey(pid) && !visited.contains(pid) && collected < maxPosts) {
              queue.add(pid)
            }
          }

          // Add replies - but SKIP if current post is OP
          if (currentId != opId || startPostDescriptor == opId) {
            node.replies.forEach { replyId ->
              if (!visited.contains(replyId) && collected < maxPosts) {
                queue.add(replyId)
              }
            }
          } else {
            // If this IS the OP, only add replies that are directly quoted by posts in our set
            log("At OP $opId - skipping bulk reply addition")
          }
        }
      }
    }

    if (!includeOp) {
      val opId = postGraph.findOP(startPostDescriptor)
      if (opId != null) {
        relevant.remove(opId)
      }
    }

    log("Collected ${relevant.size} relevant posts (mode: ${mode.name})")
    return relevant
  }

  // Helper method to get the path from OP to a specific post
  private fun getPathFromOpToPost(opId: PostDescriptor, targetPostId: PostDescriptor): List<PostDescriptor> {
    val path = mutableListOf<PostDescriptor>()
    var currentId = targetPostId
    val visited = mutableSetOf<PostDescriptor>()

    while (currentId != null && currentId != opId && !visited.contains(currentId)) {
      visited.add(currentId)
      path.add(0, currentId) // Add to the beginning to maintain order

      val node = postGraph.nodes[currentId]
      if (node == null) break

      // Find the parent that leads toward OP
      val commentText = node.data.postComment.originalComment().toString()
      val quotedParents = extractParentIds(commentText, currentId)

      // Use the earliest quoted parent as the path back to OP
      val validParents = quotedParents.filter { postGraph.nodes.containsKey(it) }
      if (validParents.isNotEmpty()) {
        currentId = validParents.minByOrNull { it.postNo } ?: break
      } else {
        break
      }
    }

    if (currentId == opId) {
      path.add(0, opId) // Add OP to the beginning
    }

    return path
  }

  fun getOrganizedThreads(): List<ReplyInfo> {
    val result = mutableListOf<ReplyInfo>()

    // Sort roots by post number to maintain chronological order
    val sortedRoots = roots.values.sortedBy { it.postDescriptor.postNo }

    for (root in sortedRoots) {
      val thread = root.getThread()
      result.addAll(thread)
    }

    return result
  }

  /**
   * Gets all posts in the conversation tree in a flat list format, maintaining the hierarchical structure
   */
  fun getAllPostsInConversation(): List<ReplyInfo> {
    return getOrganizedThreads()
  }

  fun printTree() {
    log("\nCONVERSATION TREE STRUCTURE:")

    fun printNode(reply: Reply, indent: String = "") {
      val marker = when {
        reply.isOP -> "👑"
        reply.children.isNotEmpty() -> "💬"
        else -> "💭"
      }
      val quotes = if (reply.quotedParents.isNotEmpty()) {
        " (→${reply.quotedParents.joinToString(",")})"
      } else {
        ""
      }
      log("${indent}${marker} L${reply.level}: ${reply.postDescriptor}${quotes}")

      reply.children
        .sortedBy { it.postDescriptor.postNo }
        .forEach { child -> printNode(child, indent + "  ") }
    }

    val sortedRoots = roots.values.sortedBy { it.postDescriptor.postNo }
    sortedRoots.forEach { root -> printNode(root) }
  }

  fun setDebug(enabled: Boolean) {
    debugEnabled = enabled
  }

  enum class Mode {
    AROUND,
    FULL
  }

  companion object {
    /**
     * Extracts post IDs from comment text that match the quote pattern (>>12345)
     * This function needs to be called with the proper context to create PostDescriptors
     */
    fun extractParentIds(commentText: String, contextDescriptor: PostDescriptor): Set<PostDescriptor> {
      val pattern = Regex("""&gt;&gt;(\d+)|>>(\d+)|&gt;&gt;&gt;/(\w+)/(\d+)""")
      val matches = pattern.findAll(commentText)

      val result = mutableSetOf<PostDescriptor>()

      matches.forEach { matchResult ->
        val groups = matchResult.groupValues

        // Handle >>12345 format
        val quotedPostNo = groups[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (quotedPostNo != null) {
          // Create a PostDescriptor with the same board/site context as the current post
          val descriptor = PostDescriptor.create(
            contextDescriptor.threadDescriptor(),
            quotedPostNo
          )
          result.add(descriptor)
        }

        // Handle >>12345 format (second capture group)
        val quotedPostNo2 = groups[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (quotedPostNo2 != null) {
          val descriptor = PostDescriptor.create(
            contextDescriptor.threadDescriptor(),
            quotedPostNo2
          )
          result.add(descriptor)
        }

        // Handle >>>/board/12345 format (cross-board quote)
        val boardCode = groups[3].takeIf { it.isNotEmpty() }
        val crossPostNo = groups[4].takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (boardCode != null && crossPostNo != null) {
          // For cross-board quotes, we'd need to resolve the actual thread number
          // This is a simplified version - in practice, you'd need to look up the thread
          val descriptor = PostDescriptor.create(
            contextDescriptor.siteDescriptor().siteName,
            boardCode,
            contextDescriptor.getThreadNo(), // Placeholder - would need actual thread lookup
            crossPostNo
          )
          result.add(descriptor)
        }
      }

      return result
    }
  }
}