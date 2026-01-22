package com.github.k1rakishou.model.data.post

import com.github.k1rakishou.model.data.descriptor.PostDescriptor

/**
 * Represents a reply in a conversation tree structure with parent-child relationships
 */
class Reply(
  val postDescriptor: PostDescriptor,
  val data: ChanPost,
  var level: Int = 0,
  val children: MutableList<Reply> = mutableListOf(),
  var parent: Reply? = null,
  val quotedParents: MutableList<PostDescriptor> = mutableListOf(),
  var isOP: Boolean = false
) {
  
  fun addChild(reply: Reply): Reply {
    reply.parent = this
    reply.level = this.level + 1
    this.children.add(reply)
    return reply
  }

  fun getAllDescendants(): List<Reply> {
    val result = mutableListOf<Reply>()

    fun traverse(reply: Reply) {
      result.add(reply)
      val sortedChildren = reply.children
        .sortedBy { it.postDescriptor.postNo }

      sortedChildren.forEach { child -> traverse(child) }
    }

    traverse(this)
    return result
  }

  fun findById(postDescriptor: PostDescriptor): Reply? {
    if (this.postDescriptor == postDescriptor) return this

    for (child in children) {
      val found = child.findById(postDescriptor)
      if (found != null) return found
    }
    return null
  }

  fun getThread(): List<ReplyInfo> {
    return getAllDescendants().map { reply ->
      ReplyInfo(
        postDescriptor = reply.postDescriptor,
        level = reply.level,
        data = reply.data,
        quotedParents = reply.quotedParents,
        isOP = reply.isOP,
        hasChildren = reply.children.isNotEmpty(),
        parentId = reply.parent?.postDescriptor
      )
    }
  }
}

/**
 * Informational class that represents a reply in the thread structure
 */
data class ReplyInfo(
  val postDescriptor: PostDescriptor,
  val level: Int,
  val data: ChanPost,
  val quotedParents: List<PostDescriptor>,
  val isOP: Boolean,
  val hasChildren: Boolean,
  val parentId: PostDescriptor?
)