package org.wordpress.android.ui.posts.adapters

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.drawable.Drawable
import android.support.v4.graphics.drawable.DrawableCompat
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.Main
import kotlinx.coroutines.experimental.isActive
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import org.wordpress.android.R
import org.wordpress.android.WordPress
import org.wordpress.android.fluxc.model.MediaModel
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.fluxc.model.post.PostStatus
import org.wordpress.android.ui.reader.utils.ReaderUtils
import org.wordpress.android.util.DisplayUtils
import org.wordpress.android.util.ImageUtils
import org.wordpress.android.util.image.ImageManager
import org.wordpress.android.util.image.ImageType
import org.wordpress.android.viewmodel.posts.PostListData
import org.wordpress.android.viewmodel.posts.PostListData.PostAdapterItem
import org.wordpress.android.widgets.PostListButton
import javax.inject.Inject

private const val ROW_ANIM_DURATION: Long = 150
private const val MAX_DISPLAYED_UPLOAD_PROGRESS = 90

private const val VIEW_TYPE_POST = 0
private const val VIEW_TYPE_ENDLIST_INDICATOR = 1
private const val VIEW_TYPE_LOADING = 2

/**
 * Adapter for Posts/Pages list
 */
class PostListAdapter(context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var onPostSelectedListener: OnPostSelectedListener? = null
    private var onPostButtonClickListener: OnPostButtonClickListener? = null
    private val photonWidth: Int
    private val photonHeight: Int
    private val endlistIndicatorHeight: Int

    private val showAllButtons: Boolean

    private var recyclerView: RecyclerView? = null
    private val layoutInflater: LayoutInflater

    private var postListData: PostListData? = null
    @Inject internal lateinit var imageManager: ImageManager

    private var refreshListJob: Job? = null

    interface OnPostButtonClickListener {
        fun onPostButtonClicked(buttonType: Int, postClicked: PostModel)
    }

    init {
        (context.applicationContext as WordPress).component().inject(this)

        layoutInflater = LayoutInflater.from(context)

        val displayWidth = DisplayUtils.getDisplayPixelWidth(context)
        val contentSpacing = context.resources.getDimensionPixelSize(R.dimen.content_margin)
        photonWidth = displayWidth - contentSpacing * 2
        photonHeight = context.resources.getDimensionPixelSize(R.dimen.reader_featured_image_height)

        // endlist indicator height is hard-coded here so that its horizontal line is in the middle of the fab
        endlistIndicatorHeight = DisplayUtils.dpToPx(context, 74)

        // on larger displays we can always show all buttons
        showAllButtons = displayWidth >= 1080
    }

    fun setPostListData(listData: PostListData) {
        refreshListJob?.cancel()
        refreshListJob = GlobalScope.launch(Dispatchers.Default) {
            val diffResult = calculateDiff(postListData, listData)
            if (isActive) {
                GlobalScope.launch(Dispatchers.Main) {
                    // Save and restore the visible view. Without this the scroll position might be lost during inserts
                    val recyclerViewState = recyclerView?.layoutManager?.onSaveInstanceState()
                    postListData = listData
                    diffResult.dispatchUpdatesTo(this@PostListAdapter)
                    recyclerViewState?.let {
                        recyclerView?.layoutManager?.onRestoreInstanceState(it)
                    }
                }
            }
        }
    }

    fun mediaChanged(mediaList: List<MediaModel>) {
//        mediaList.forEach { mediaModel ->
//            listManager?.findWithIndex { post ->
//                post.featuredImageId == mediaModel.mediaId
//            }?.forEach { (position, _) ->
//                notifyItemChanged(position)
//            }
//        }
    }

    fun setOnPostSelectedListener(listener: OnPostSelectedListener) {
        onPostSelectedListener = listener
    }

    fun setOnPostButtonClickListener(listener: OnPostButtonClickListener) {
        onPostButtonClickListener = listener
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun getItemViewType(position: Int): Int {
        return when {
            position == (itemCount - 1) -> VIEW_TYPE_ENDLIST_INDICATOR
            postListData?.getItem(
                    position,
                    shouldFetchIfNull = true,
                    shouldLoadMoreIfNecessary = false
            ) != null -> VIEW_TYPE_POST
            else -> VIEW_TYPE_LOADING
        }
    }

    override fun getItemCount(): Int {
        postListData?.let {
            return if (it.size == 0) {
                0
            } else {
                it.size + 1 // +1 for the endlist indicator
            }
        }
        return 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_ENDLIST_INDICATOR -> {
                val view = layoutInflater.inflate(R.layout.endlist_indicator, parent, false)
                view.layoutParams.height = endlistIndicatorHeight
                EndListViewHolder(view)
            }
            VIEW_TYPE_LOADING -> {
                val view = layoutInflater.inflate(R.layout.post_cardview_skeleton, parent, false)
                LoadingViewHolder(view)
            }
            VIEW_TYPE_POST -> {
                val view = layoutInflater.inflate(R.layout.post_cardview, parent, false)
                PostViewHolder(view)
            }
            else -> {
                // Fail fast if a new view type is added so the we can handle it
                throw IllegalStateException("The view type '$viewType' needs to be handled")
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // nothing to do if this is the static endlist indicator
        if (holder is EndListViewHolder) {
            return
        }
        if (holder is LoadingViewHolder) {
            return
        }
        if (holder !is PostViewHolder) {
            // Fail fast if a new view type is added so the we can handle it
            throw IllegalStateException("Only remaining ViewHolder type should be PostViewHolder")
        }

        postListData?.getItem(position, true, true)?.let { postAdapterItem ->
            val context = holder.itemView.context

            holder.title.text = if (!postAdapterItem.title.isNullOrBlank()) {
                postAdapterItem.title
            } else context.getString(R.string.untitled_in_parentheses)

            if (!postAdapterItem.excerpt.isNullOrBlank()) {
                holder.excerpt.text = postAdapterItem.excerpt
                holder.excerpt.visibility = View.VISIBLE
            } else {
                holder.excerpt.visibility = View.GONE
            }

            showFeaturedImage(postAdapterItem.featuredImageUrl, holder.featuredImage)

            // local drafts say "delete" instead of "trash"
            if (postAdapterItem.isLocalDraft) {
                holder.date.visibility = View.GONE
                holder.trashButton.buttonType = PostListButton.BUTTON_DELETE
            } else {
                holder.date.text = postAdapterItem.date
                holder.date.visibility = View.VISIBLE
                holder.trashButton.buttonType = PostListButton.BUTTON_TRASH
            }

            // Move the overlay calculation to PostAdapterItem
            if (postAdapterItem.isUploading) {
                holder.disabledOverlay.visibility = View.VISIBLE
                holder.progressBar.isIndeterminate = true
            } else if (postListData?.isAztecEditorEnabled == false && postAdapterItem.isUploadingOrQueued) {
                // TODO: Is this logic correct? Do we need to check for is uploading still?
                // Editing posts with uploading media is only supported in Aztec
                holder.disabledOverlay.visibility = View.VISIBLE
            } else {
                holder.progressBar.isIndeterminate = false
                holder.disabledOverlay.visibility = View.GONE
            }

            updateStatusTextAndImage(holder.status, holder.statusImage, postAdapterItem)
            updatePostUploadProgressBar(postAdapterItem, holder.progressBar)
            configurePostButtons(holder, postAdapterItem)
            holder.itemView.setOnClickListener {
                // TODO: move to adapter
//                onPostSelectedListener?.onPostSelected(post)
            }
        }
    }

    private fun showFeaturedImage(imageUrl: String?, imgFeatured: ImageView) {
        if (imageUrl == null) {
            imgFeatured.visibility = View.GONE
            imageManager.cancelRequestAndClearImageView(imgFeatured)
        } else if (imageUrl.startsWith("http")) {
            val photonUrl = ReaderUtils.getResizedImageUrl(
                    imageUrl, photonWidth, photonHeight, postListData?.isPhotonCapable == false
            )
            imgFeatured.visibility = View.VISIBLE
            imageManager.load(imgFeatured, ImageType.PHOTO, photonUrl, ScaleType.CENTER_CROP)
        } else {
            val bmp = ImageUtils.getWPImageSpanThumbnailFromFilePath(
                    imgFeatured.context, imageUrl, photonWidth
            )
            if (bmp != null) {
                imgFeatured.visibility = View.VISIBLE
                imageManager.load(imgFeatured, bmp)
            } else {
                imgFeatured.visibility = View.GONE
                imageManager.cancelRequestAndClearImageView(imgFeatured)
            }
        }
    }

    private fun updatePostUploadProgressBar(postAdapterItem: PostAdapterItem, view: ProgressBar) {
        if (!postAdapterItem.isUploadFailed &&
                (postAdapterItem.isUploadingOrQueued || postAdapterItem.hasInProgressMediaUpload)) {
            view.visibility = View.VISIBLE
            // Sometimes the progress bar can be stuck at 100% for a long time while further processing happens
            // Cap the progress bar at MAX_DISPLAYED_UPLOAD_PROGRESS (until we move past the 'uploading media' phase)
            view.progress = Math.min(MAX_DISPLAYED_UPLOAD_PROGRESS, postAdapterItem.mediaUploadProgress)
        } else {
            view.visibility = View.GONE
        }
    }

    private fun updateStatusTextAndImage(txtStatus: TextView, imgStatus: ImageView, postAdapterItem: PostAdapterItem) {
        val context = txtStatus.context

        if (postAdapterItem.postStatus == PostStatus.PUBLISHED && !postAdapterItem.isLocalDraft &&
                !postAdapterItem.isLocallyChanged) {
            txtStatus.visibility = View.GONE
            imgStatus.visibility = View.GONE
            imageManager.cancelRequestAndClearImageView(imgStatus)
        } else {
            var statusTextResId = 0
            var statusIconResId = 0
            var statusColorResId = R.color.grey_darken_10
            var errorMessage: String? = null

            if (postAdapterItem.uploadError != null && !postAdapterItem.hasInProgressMediaUpload) {
                if (postAdapterItem.uploadError.mediaError != null) {
                    errorMessage = context.getString(R.string.error_media_recover_post)
                } else if (postAdapterItem.uploadError.postError != null) {
                    // TODO: figure out!!
//                    errorMessage = UploadUtils.getErrorMessageFromPostError(context, post, reason.postError)
                }
                statusIconResId = R.drawable.ic_gridicons_cloud_upload
                statusColorResId = R.color.alert_red
            } else if (postAdapterItem.isUploading) {
                statusTextResId = R.string.post_uploading
                statusIconResId = R.drawable.ic_gridicons_cloud_upload
            } else if (postAdapterItem.hasInProgressMediaUpload) {
                statusTextResId = R.string.uploading_media
                statusIconResId = R.drawable.ic_gridicons_cloud_upload
            } else if (postAdapterItem.isQueued || postAdapterItem.hasPendingMediaUpload) {
                // the Post (or its related media if such a thing exist) *is strictly* queued
                statusTextResId = R.string.post_queued
                statusIconResId = R.drawable.ic_gridicons_cloud_upload
            } else if (postAdapterItem.isLocalDraft) {
                statusTextResId = R.string.local_draft
                statusIconResId = R.drawable.ic_gridicons_page
                statusColorResId = R.color.alert_yellow_dark
            } else if (postAdapterItem.isLocallyChanged) {
                statusTextResId = R.string.local_changes
                statusIconResId = R.drawable.ic_gridicons_page
                statusColorResId = R.color.alert_yellow_dark
            } else {
                when (postAdapterItem.postStatus) {
                    PostStatus.DRAFT -> {
                        statusTextResId = R.string.post_status_draft
                        statusIconResId = R.drawable.ic_gridicons_page
                        statusColorResId = R.color.alert_yellow_dark
                    }
                    PostStatus.PRIVATE -> statusTextResId = R.string.post_status_post_private
                    PostStatus.PENDING -> {
                        statusTextResId = R.string.post_status_pending_review
                        statusIconResId = R.drawable.ic_gridicons_page
                        statusColorResId = R.color.alert_yellow_dark
                    }
                    PostStatus.SCHEDULED -> {
                        statusTextResId = R.string.post_status_scheduled
                        statusIconResId = R.drawable.ic_gridicons_calendar
                        statusColorResId = R.color.blue_medium
                    }
                    PostStatus.TRASHED -> {
                        statusTextResId = R.string.post_status_trashed
                        statusIconResId = R.drawable.ic_gridicons_page
                        statusColorResId = R.color.alert_red
                    }
                    PostStatus.UNKNOWN -> {
                    }
                    PostStatus.PUBLISHED -> {
                    }
                    else ->
                        // no-op
                        return
                }
            }

            val resources = context.resources
            txtStatus.setTextColor(resources.getColor(statusColorResId))
            if (!TextUtils.isEmpty(errorMessage)) {
                txtStatus.text = errorMessage
            } else {
                txtStatus.text = if (statusTextResId != 0) resources.getString(statusTextResId) else ""
            }
            txtStatus.visibility = View.VISIBLE

            var drawable: Drawable? = if (statusIconResId != 0) resources.getDrawable(statusIconResId) else null
            if (drawable != null) {
                drawable = DrawableCompat.wrap(drawable)
                DrawableCompat.setTint(drawable, resources.getColor(statusColorResId))
                imgStatus.visibility = View.VISIBLE
                imageManager.load(imgStatus, drawable)
            } else {
                imgStatus.visibility = View.GONE
                imageManager.cancelRequestAndClearImageView(imgStatus)
            }
        }
    }

    private fun configurePostButtons(
        holder: PostViewHolder,
        postAdapterItem: PostAdapterItem
    ) {
        val canRetry = postAdapterItem.uploadError != null && !postAdapterItem.hasInProgressMediaUpload
        val canShowViewButton = !canRetry
        val canShowPublishButton = canRetry || postAdapterItem.canPublishPost

        // publish button is re-purposed depending on the situation
        if (canShowPublishButton) {
            if (postListData?.hasCapabilityPublishPosts == false) {
                holder.publishButton.buttonType = PostListButton.BUTTON_SUBMIT
            } else if (canRetry) {
                holder.publishButton.buttonType = PostListButton.BUTTON_RETRY
            } else if (postAdapterItem.postStatus == PostStatus.SCHEDULED && postAdapterItem.isLocallyChanged) {
                holder.publishButton.buttonType = PostListButton.BUTTON_SYNC
            } else {
                holder.publishButton.buttonType = PostListButton.BUTTON_PUBLISH
            }
        }

        // posts with local changes have preview rather than view button
        if (canShowViewButton) {
            if (postAdapterItem.isLocalDraft || postAdapterItem.isLocallyChanged) {
                holder.viewButton.buttonType = PostListButton.BUTTON_PREVIEW
            } else {
                holder.viewButton.buttonType = PostListButton.BUTTON_VIEW
            }
        }

        // edit is always visible
        holder.editButton.visibility = View.VISIBLE
        holder.viewButton.visibility = if (canShowViewButton) View.VISIBLE else View.GONE

        var numVisibleButtons = 2
        if (canShowViewButton) {
            numVisibleButtons++
        }
        if (canShowPublishButton) {
            numVisibleButtons++
        }
        if (postAdapterItem.canShowStats) {
            numVisibleButtons++
        }

        // if there's enough room to show all buttons then hide back/more and show stats/trash/publish,
        // otherwise show the more button and hide stats/trash/publish
        if (showAllButtons || numVisibleButtons <= 3) {
            holder.moreButton.visibility = View.GONE
            holder.backButton.visibility = View.GONE
            holder.trashButton.visibility = View.VISIBLE
            holder.statsButton.visibility = if (postAdapterItem.canShowStats) View.VISIBLE else View.GONE
            holder.publishButton.visibility = if (canShowPublishButton) View.VISIBLE else View.GONE
        } else {
            holder.moreButton.visibility = View.VISIBLE
            holder.backButton.visibility = View.GONE
            holder.trashButton.visibility = View.GONE
            holder.statsButton.visibility = View.GONE
            holder.publishButton.visibility = View.GONE
        }

        val btnClickListener = View.OnClickListener { view ->
            // handle back/more here, pass other actions to activity/fragment
            val buttonType = (view as PostListButton).buttonType
            when (buttonType) {
                PostListButton.BUTTON_MORE -> animateButtonRows(holder, postAdapterItem, false)
                PostListButton.BUTTON_BACK -> animateButtonRows(holder, postAdapterItem, true)
                else -> if (onPostButtonClickListener != null) {
                    // TODO: Move to adapter item
//                    onPostButtonClickListener?.onPostButtonClicked(buttonType, post)
                }
            }
        }
        holder.editButton.setOnClickListener(btnClickListener)
        holder.viewButton.setOnClickListener(btnClickListener)
        holder.statsButton.setOnClickListener(btnClickListener)
        holder.trashButton.setOnClickListener(btnClickListener)
        holder.moreButton.setOnClickListener(btnClickListener)
        holder.backButton.setOnClickListener(btnClickListener)
        holder.publishButton.setOnClickListener(btnClickListener)
    }

    /*
     * buttons may appear in two rows depending on display size and number of visible
     * buttons - these rows are toggled through the "more" and "back" buttons - this
     * routine is used to animate the new row in and the old row out
     */
    private fun animateButtonRows(
        holder: PostViewHolder,
        postAdapterItem: PostAdapterItem,
        showRow1: Boolean
    ) {
        // first animate out the button row, then show/hide the appropriate buttons,
        // then animate the row layout back in
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0f)
        val animOut = ObjectAnimator.ofPropertyValuesHolder(holder.buttonsLayout, scaleX, scaleY)
        animOut.duration = ROW_ANIM_DURATION
        animOut.interpolator = AccelerateInterpolator()

        animOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // row 1
                holder.editButton.visibility = if (showRow1) View.VISIBLE else View.GONE
                holder.viewButton.visibility = if (showRow1) View.VISIBLE else View.GONE
                holder.moreButton.visibility = if (showRow1) View.VISIBLE else View.GONE
                // row 2
                holder.statsButton.visibility = if (!showRow1 && postAdapterItem.canShowStats) {
                    View.VISIBLE
                } else View.GONE
                holder.publishButton.visibility = if (!showRow1 && postAdapterItem.canPublishPost) {
                    View.VISIBLE
                } else View.GONE
                holder.trashButton.visibility = if (!showRow1) View.VISIBLE else View.GONE
                holder.backButton.visibility = if (!showRow1) View.VISIBLE else View.GONE

                val updatedScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0f, 1f)
                val updatedScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f, 1f)
                val animIn = ObjectAnimator.ofPropertyValuesHolder(holder.buttonsLayout, updatedScaleX, updatedScaleY)
                animIn.duration = ROW_ANIM_DURATION
                animIn.interpolator = DecelerateInterpolator()
                animIn.start()
            }
        })

        animOut.start()
    }

    fun refreshRowForPost(post: PostModel) {
//        getPositionForPost(post)?.let { position ->
//            notifyItemChanged(position)
//        }
    }

//    fun updateProgressForPost(post: PostModel) {
//        recyclerView?.let { recycler ->
//            getPositionForPost(post)?.let { position ->
//                val viewHolder = recycler.findViewHolderForAdapterPosition(position)
//                if (viewHolder is PostViewHolder) {
//                    updatePostUploadProgressBar(viewHolder.progressBar, post)
//                }
//            }
//        }
//    }

    fun getPositionForPost(post: PostModel): Int? = null
//            listManager?.findWithIndex {
//                if (post.isLocalDraft) it.id == post.id else it.remotePostId == post.remotePostId
//            }?.asSequence()?.map { it.first }?.firstOrNull()

    interface OnPostSelectedListener {
        fun onPostSelected(post: PostModel)
    }

    private class PostViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_title)
        val excerpt: TextView = view.findViewById(R.id.text_excerpt)
        val date: TextView = view.findViewById(R.id.text_date)
        val status: TextView = view.findViewById(R.id.text_status)

        val statusImage: ImageView = view.findViewById(R.id.image_status)
        val featuredImage: ImageView = view.findViewById(R.id.image_featured)

        val editButton: PostListButton = view.findViewById(R.id.btn_edit)
        val viewButton: PostListButton = view.findViewById(R.id.btn_view)
        val publishButton: PostListButton = view.findViewById(R.id.btn_publish)
        val moreButton: PostListButton = view.findViewById(R.id.btn_more)
        val statsButton: PostListButton = view.findViewById(R.id.btn_stats)
        val trashButton: PostListButton = view.findViewById(R.id.btn_trash)
        val backButton: PostListButton = view.findViewById(R.id.btn_back)
        val buttonsLayout: ViewGroup = view.findViewById(R.id.layout_buttons)

        val disabledOverlay: View = view.findViewById(R.id.disabled_overlay)
        val progressBar: ProgressBar = view.findViewById(R.id.post_upload_progress)
    }

    private class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)
    private class EndListViewHolder(view: View) : RecyclerView.ViewHolder(view)
}

private suspend fun calculateDiff(
    oldListData: PostListData?,
    newListData: PostListData
) = withContext(Dispatchers.Default) {
    val callback = object : DiffUtil.Callback() {
        override fun getOldListSize(): Int {
            return oldListData?.size ?: 0
        }

        override fun getNewListSize(): Int {
            return newListData.size
        }

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldRemoteItemId = oldListData?.listManager?.getRemoteItemId(oldItemPosition)
            val newRemoteItemId = newListData.listManager.getRemoteItemId(newItemPosition)
            if (oldRemoteItemId != null && newRemoteItemId != null) {
                // both remote items
                return oldRemoteItemId == newRemoteItemId
            }
            // We shouldn't fetch items or load more pages prematurely when we are just trying to compare them
            val oldItem = oldListData?.listManager?.getItem(
                    position = oldItemPosition,
                    shouldFetchIfNull = false,
                    shouldLoadMoreIfNecessary = false
            )
            val newItem = newListData.listManager.getItem(
                    position = newItemPosition,
                    shouldFetchIfNull = false,
                    shouldLoadMoreIfNecessary = false
            )
            if (oldItem == null || newItem == null) {
                // One remote and one local item. The remote item is not fetched yet, it can't be the same items.
                return false
            }
            // Either one remote item and one local item or both local items. In either case, we'll let the caller
            // decide how to compare the two. In most cases, a local id comparison should be enough.
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldListData?.getItem(oldItemPosition) == newListData.getItem(newItemPosition)
        }
    }
    DiffUtil.calculateDiff(callback)
}
