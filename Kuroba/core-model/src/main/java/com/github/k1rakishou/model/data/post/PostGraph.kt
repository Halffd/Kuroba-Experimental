package com.github.k1rakishou.model.data.post

import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.core_logger.Logger

/**
 * Graph structure to track post relationships and build conversation trees
 */
class PostGraph {
  val nodes = mutableMapOf<PostDescriptor, PostGraphNode>()
  val roots = mutableSetOf<PostDescriptor>() // Root posts (OPs or posts with no parents)
  private val reverseQuoteIndex = mutableMapOf<PostDescriptor, MutableSet<PostDescriptor>>() // postId -> Set of postIds that quote it
  private var debugEnabled = false

  fun log(message: String) {
    if (debugEnabled) {
      Logger.d("PostGraph", message)
    }
  }

  fun addPost(postDescriptor: PostDescriptor, data: ChanPost, parentIds: Set<PostDescriptor> = emptySet()) {
    val node = nodes.getOrPut(postDescriptor) {
      PostGraphNode(data, mutableSetOf(), mutableSetOf())
    }

    // Add new parents
    parentIds.forEach { parentId ->
      if (parentId != null) node.parents.add(parentId)
    }

    // Update parent nodes to include this as a reply
    for (parentId in node.parents) {
      if (nodes.containsKey(parentId)) {
        nodes[parentId]?.replies?.add(postDescriptor)
      }
    }

    // Determine if it's a root
    if (node.parents.isEmpty()) {
      roots.add(postDescriptor)
    } else {
      roots.remove(postDescriptor)
    }
  }

  // Find the "primary" root (earliest post number among parents)
  fun findRoot(postDescriptor: PostDescriptor): PostDescriptor? {
    val visited = mutableSetOf<PostDescriptor>()

    fun dfs(currentId: PostDescriptor): PostDescriptor {
      if (visited.contains(currentId)) return currentId
      visited.add(currentId)

      val node = nodes[currentId]
      if (node == null || node.parents.isEmpty()) {
        return currentId
      }

      // Find the earliest parent (lowest post number)
      val sortedParents = node.parents.sortedBy { it.postNo }
      return dfs(sortedParents.first()) // Follow primary parent chain
    }

    return dfs(postDescriptor)
  }

  // DFS to find OP/root from any post
  fun findOP(postDescriptor: PostDescriptor): PostDescriptor? {
    val visited = mutableSetOf<PostDescriptor>()
    val path = mutableListOf<PostDescriptor>()

    log("Finding OP from post: $postDescriptor")

    fun dfs(currentId: PostDescriptor): PostDescriptor? {
      // Cycle detection
      if (visited.contains(currentId)) {
        log("Cycle detected in path: ${path.joinToString(" -> ")} -> $currentId")
        return currentId // Return current as fallback
      }

      visited.add(currentId)
      path.add(currentId)

      val node = nodes[currentId]
      if (node == null) {
        log("Node $currentId not found")
        return currentId
      }

      // Check if this is OP (no parents OR marked as OP)
      val hasNoParents = node.parents.isEmpty()
      val isMarkedAsOP = node.data.isOP()

      if (hasNoParents || isMarkedAsOP) {
        log("Found OP: $currentId (path: ${path.joinToString(" -> ")})")
        return currentId
      }

      // Go up parent chain - use first/primary parent
      var nextParent: PostDescriptor? = null
      if (node.parents.isNotEmpty()) {
        // Use earliest parent (lowest post number)
        val sortedParents = node.parents.sortedBy { it.postNo }
        nextParent = sortedParents.firstOrNull()
      }

      if (nextParent != null) {
        log("Following parent chain: $currentId -> $nextParent")
        return dfs(nextParent)
      } else {
        // No parent found, this must be root
        log("No parent found, $currentId is root")
        return currentId
      }
    }

    val opId = dfs(postDescriptor)
    log("OP for post $postDescriptor: $opId")
    return opId
  }

  fun buildFromPosts(posts: List<ChanPost>) {
    posts.forEach { post ->
      // Extract quote relationships from the post comment
      val quoteDescriptors = extractQuotesFromPost(post)
      addPost(post.postDescriptor, post, quoteDescriptors)
    }
  }

  private fun extractQuotesFromPost(post: ChanPost): Set<PostDescriptor> {
    val commentText = post.postComment.originalComment().toString()
    val pattern = Regex("""&gt;&gt;(\d+)|>>(\d+)|&gt;&gt;&gt;/(\w+)/(\d+)""")
    val matches = pattern.findAll(commentText)
    
    val result = mutableSetOf<PostDescriptor>()
    
    matches.forEach { matchResult ->
      val groups = matchResult.groupValues

      // Handle >>12345 format
      val quotedPostNo = groups[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
      if (quotedPostNo != null) {
        val descriptor = PostDescriptor.create(
          post.postDescriptor.threadDescriptor(),
          quotedPostNo
        )
        result.add(descriptor)
      }

      // Handle >>12345 format (second capture group)
      val quotedPostNo2 = groups[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
      if (quotedPostNo2 != null) {
        val descriptor = PostDescriptor.create(
          post.postDescriptor.threadDescriptor(),
          quotedPostNo2
        )
        result.add(descriptor)
      }

      // Handle >>>/board/12345 format (cross-board quote)
      val boardCode = groups[3].takeIf { it.isNotEmpty() }
      val crossPostNo = groups[4].takeIf { it.isNotEmpty() }?.toLongOrNull()
      if (boardCode != null && crossPostNo != null) {
        val descriptor = PostDescriptor.create(
          post.postDescriptor.siteDescriptor().siteName,
          boardCode,
          post.postDescriptor.getThreadNo(), // Placeholder - would need actual thread lookup
          crossPostNo
        )
        result.add(descriptor)
      }
    }
    
    return result
  }

  fun getReplyChainFromPost(
    startPostDescriptor: PostDescriptor,
    includeOP: Boolean = true,
    around: Boolean = false
  ): List<ReplyInfo> {
    log("Getting organized conversation for $startPostDescriptor")
    val mode = if (around) ConversationTree.Mode.AROUND else ConversationTree.Mode.FULL
    val tree = ConversationTree(this)
    tree.setDebug(debugEnabled)
    tree.buildFromPostGraph(startPostDescriptor, mode, includeOP)

    if (debugEnabled) {
      tree.printTree()
    }

    return tree.getOrganizedThreads()
  }

  /**
   * Gets the full conversation tree for the entire thread starting from the OP
   */
  fun getFullConversationTree(opPostDescriptor: PostDescriptor): List<ReplyInfo> {
    log("Getting full conversation tree for thread starting from $opPostDescriptor")
    val tree = ConversationTree(this)
    tree.setDebug(debugEnabled)
    tree.buildFromPostGraph(opPostDescriptor, ConversationTree.Mode.FULL, includeOp = true)

    if (debugEnabled) {
      tree.printTree()
    }

    return tree.getAllPostsInConversation()
  }

  fun setDebug(enabled: Boolean) {
    debugEnabled = enabled
  }
}

data class PostGraphNode(
  val data: ChanPost,
  val replies: MutableSet<PostDescriptor>,
  val parents: MutableSet<PostDescriptor>
)