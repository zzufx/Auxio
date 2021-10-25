/*
 * Copyright (c) 2021 Auxio Project
 * MainFragment.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.home

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.iterator
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import org.oxycblt.auxio.MainFragmentDirections
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.list.AlbumListFragment
import org.oxycblt.auxio.home.list.ArtistListFragment
import org.oxycblt.auxio.home.list.GenreListFragment
import org.oxycblt.auxio.home.list.SongListFragment
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Genre
import org.oxycblt.auxio.music.MusicStore
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.DisplayMode
import org.oxycblt.auxio.ui.SortMode
import org.oxycblt.auxio.util.applyEdge
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.logE

/**
 * The main "Launching Point" fragment of Auxio, allowing navigation to the detail
 * views for each respective fragment.
 * @author OxygenCobalt
 */
class HomeFragment : Fragment() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentHomeBinding.inflate(inflater)
        var bottomPadding = 0
        val sortItem: MenuItem

        // Build the permission launcher here as you can only do it in onCreateView/onCreate
        val permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            homeModel.reloadMusic(requireContext())
        }

        // --- UI SETUP ---

        binding.lifecycleOwner = viewLifecycleOwner

        binding.applyEdge { bars ->
            bottomPadding = bars.bottom
            updateFabPadding(binding, bottomPadding)
            binding.homeAppbar.updatePadding(top = bars.top)
        }

        binding.homeAppbar.apply {
            // I have no idea how to clip the collapsing toolbar while still making the elevation
            // overlay bleed into the status bar, so I take the easy way out and just fade the
            // toolbar when the offset changes.
            // Note: Don't merge this with the other OnOffsetChangedListener, as this one needs
            // to be added pre-start to work correctly while the other one needs to be posted to
            // work correctly
            addOnOffsetChangedListener(
                AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
                    binding.homeToolbar.alpha = (binding.homeToolbar.height + verticalOffset) /
                        binding.homeToolbar.height.toFloat()
                }
            )

            // One issue that comes with using our fast scroller is that it allows scrolling
            // without the AppBar actually being collapsed in the process. This results in
            // the RecyclerView being clipped if you scroll down far enough. To fix this, we
            // add another OnOffsetChangeListener that adds padding to the RecyclerView whenever
            // the Toolbar is collapsed. This is not really ideal, as it forces a relayout and
            // some edge-effect glitches whenever we scroll, but its the best we can do.
            post {
                val vOffset = (
                    (layoutParams as CoordinatorLayout.LayoutParams)
                        .behavior as AppBarLayout.Behavior
                    ).topAndBottomOffset

                binding.homePager.updatePaddingRelative(
                    bottom = binding.homeAppbar.totalScrollRange + vOffset
                )

                binding.homeAppbar.addOnOffsetChangedListener(
                    AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
                        binding.homePager.updatePaddingRelative(
                            bottom = binding.homeAppbar.totalScrollRange + verticalOffset
                        )
                    }
                )
            }
        }

        binding.homeToolbar.apply {
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_search -> {
                        findNavController().navigate(HomeFragmentDirections.actionShowSearch())
                    }

                    R.id.action_settings -> {
                        parentFragment?.parentFragment?.findNavController()?.navigate(
                            MainFragmentDirections.actionShowSettings()
                        )
                    }

                    R.id.action_about -> {
                        parentFragment?.parentFragment?.findNavController()?.navigate(
                            MainFragmentDirections.actionShowAbout()
                        )
                    }

                    R.id.submenu_sorting -> { }

                    // Sorting option was selected, mark it as selected and update the mode
                    else -> {
                        item.isChecked = true

                        homeModel.updateCurrentSort(
                            requireNotNull(SortMode.fromId(item.itemId))
                        )
                    }
                }

                true
            }

            sortItem = menu.findItem(R.id.submenu_sorting)
        }

        binding.homePager.apply {
            adapter = HomePagerAdapter()

            // By default, ViewPager2's sensitivity is high enough to result in vertical
            // scroll events being registered as horizontal scroll events. Reflect into the
            // internal recyclerview and change the touch slope so that touch actions will
            // act more as a scroll than as a swipe.
            // Derived from: https://al-e-shevelev.medium.com/how-to-reduce-scroll-sensitivity-of-viewpager2-widget-87797ad02414
            try {
                val recycler = ViewPager2::class.java.getDeclaredField("mRecyclerView").run {
                    isAccessible = true
                    get(binding.homePager)
                }

                RecyclerView::class.java.getDeclaredField("mTouchSlop").apply {
                    isAccessible = true

                    val slop = get(recycler) as Int
                    set(recycler, slop * 3) // 3x seems to be the best fit here
                }
            } catch (e: Exception) {
                logE("Unable to reduce ViewPager sensitivity")
                logE(e.stackTraceToString())
            }

            // We know that there will only be a fixed amount of tabs, so we manually set this
            // limit to that. This also prevents the appbar lift state from being confused during
            // page transitions.
            offscreenPageLimit = homeModel.tabs.size

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = homeModel.updateCurrentTab(position)
            })

            TabLayoutMediator(binding.homeTabs, this) { tab, pos ->
                tab.setText(homeModel.tabs[pos].string)
            }.attach()
        }

        binding.homeFab.setOnClickListener {
            playbackModel.shuffleAll()
        }

        // --- VIEWMODEL SETUP ---

        // Initialize music loading. Unlike MainFragment, we can not only do this here on startup
        // but also show a SnackBar in a reasonable place in this fragment.
        homeModel.loadMusic(requireContext())

        // There is no way a fast scrolling event can continue across a re-create. Reset it.
        homeModel.updateFastScrolling(false)

        homeModel.loaderResponse.observe(viewLifecycleOwner) { response ->
            // Handle the loader response.
            when (response) {
                is MusicStore.Response.Ok -> {
                    logD("Received Ok")

                    binding.homeFab.show()
                    playbackModel.setupPlayback(requireContext())
                }

                is MusicStore.Response.Err -> {
                    logD("Received Error")

                    // We received an error. Hide the FAB and show a Snackbar with the error
                    // message and a corresponding action
                    binding.homeFab.hide()

                    val errorRes = when (response.kind) {
                        MusicStore.ErrorKind.NO_MUSIC -> R.string.err_no_music
                        MusicStore.ErrorKind.NO_PERMS -> R.string.err_no_perms
                        MusicStore.ErrorKind.FAILED -> R.string.err_load_failed
                    }

                    val snackbar = Snackbar.make(
                        binding.root, getString(errorRes), Snackbar.LENGTH_INDEFINITE
                    )

                    snackbar.view.apply {
                        // Change the font family to our semibold color
                        findViewById<Button>(
                            com.google.android.material.R.id.snackbar_action
                        ).typeface = ResourcesCompat.getFont(requireContext(), R.font.inter_semibold)

                        fitsSystemWindows = false

                        // Prevent fitsSystemWindows margins from being applied to this view
                        // [We already do it]
                        setOnApplyWindowInsetsListener { v, insets -> insets }
                    }

                    when (response.kind) {
                        MusicStore.ErrorKind.FAILED, MusicStore.ErrorKind.NO_MUSIC -> {
                            snackbar.setAction(R.string.lbl_retry) {
                                homeModel.reloadMusic(requireContext())
                            }
                        }

                        MusicStore.ErrorKind.NO_PERMS -> {
                            snackbar.setAction(R.string.lbl_grant) {
                                permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }
                    }

                    snackbar.show()
                }

                // While loading or during an error, make sure we keep the shuffle fab hidden so
                // that any kind of loading is impossible. PlaybackStateManager also relies on this
                // invariant, so please don't change it.
                null -> binding.homeFab.hide()
            }
        }

        homeModel.fastScrolling.observe(viewLifecycleOwner) { scrolling ->
            // Make sure an update here doesn't mess up the FAB state when it comes to the
            // loader response.
            if (homeModel.loaderResponse.value !is MusicStore.Response.Ok) {
                return@observe
            }

            if (scrolling) {
                binding.homeFab.hide()
            } else {
                binding.homeFab.show()
            }
        }

        homeModel.recreateTabs.observe(viewLifecycleOwner) { recreate ->
            // notifyDataSetChanged is not practical for recreating here since it will cache
            // the previous fragments. Just instantiate a whole new adapter.
            if (recreate) {
                binding.homePager.currentItem = 0
                binding.homePager.adapter = HomePagerAdapter()
                homeModel.finishRecreateTabs()
            }
        }

        homeModel.curTab.observe(viewLifecycleOwner) { t ->
            val tab = requireNotNull(t)

            // Make sure that we update the scrolling view and allowed menu items before whenever
            // the tab changes.
            when (tab) {
                DisplayMode.SHOW_SONGS -> updateSortMenu(sortItem, tab)

                DisplayMode.SHOW_ALBUMS -> updateSortMenu(sortItem, tab) { id ->
                    id != R.id.option_sort_album
                }

                DisplayMode.SHOW_ARTISTS -> updateSortMenu(sortItem, tab) { id ->
                    id == R.id.option_sort_asc || id == R.id.option_sort_dsc
                }

                DisplayMode.SHOW_GENRES -> updateSortMenu(sortItem, tab) { id ->
                    id == R.id.option_sort_asc || id == R.id.option_sort_dsc
                }
            }

            binding.homeAppbar.liftOnScrollTargetViewId = tab.viewId
        }

        detailModel.navToItem.observe(viewLifecycleOwner) { item ->
            // The AppBarLayout gets confused and collapses when we navigate too fast, wait for it
            // to draw before we continue.
            binding.homeAppbar.post {
                when (item) {
                    is Song -> findNavController().navigate(
                        HomeFragmentDirections.actionShowAlbum(item.album.id)
                    )

                    is Album -> findNavController().navigate(
                        HomeFragmentDirections.actionShowAlbum(item.id)
                    )

                    is Artist -> findNavController().navigate(
                        HomeFragmentDirections.actionShowArtist(item.id)
                    )

                    is Genre -> findNavController().navigate(
                        HomeFragmentDirections.actionShowGenre(item.id)
                    )

                    else -> {
                    }
                }
            }
        }

        playbackModel.song.observe(viewLifecycleOwner) {
            updateFabPadding(binding, bottomPadding)
        }

        logD("Fragment Created.")

        return binding.root
    }

    private fun updateSortMenu(
        item: MenuItem,
        displayMode: DisplayMode,
        isVisible: (Int) -> Boolean = { true }
    ) {
        val toHighlight = homeModel.getSortForDisplay(displayMode)

        for (option in item.subMenu) {
            if (option.itemId == toHighlight.itemId) {
                option.isChecked = true
            }

            option.isVisible = isVisible(option.itemId)
        }
    }

    private fun updateFabPadding(
        binding: FragmentHomeBinding,
        bottomPadding: Int
    ) {
        // To get our FAB to work with edge-to-edge, we need keep track of the bar view and update
        // the padding based off of that. However, we can't use the shared method here since FABs
        // don't respect padding, so we duplicate the code here except with the margins instead.
        val fabParams = binding.homeFab.layoutParams as CoordinatorLayout.LayoutParams
        val baseSpacing = resources.getDimensionPixelSize(R.dimen.spacing_medium)

        if (playbackModel.song.value == null) {
            fabParams.bottomMargin = baseSpacing + bottomPadding
        } else {
            fabParams.bottomMargin = baseSpacing
        }
    }

    private val DisplayMode.viewId: Int get() = when (this) {
        DisplayMode.SHOW_SONGS -> R.id.home_song_list
        DisplayMode.SHOW_ALBUMS -> R.id.home_album_list
        DisplayMode.SHOW_ARTISTS -> R.id.home_artist_list
        DisplayMode.SHOW_GENRES -> R.id.home_genre_list
    }

    private inner class HomePagerAdapter :
        FragmentStateAdapter(childFragmentManager, viewLifecycleOwner.lifecycle) {

        override fun getItemCount(): Int = homeModel.tabs.size

        override fun createFragment(position: Int): Fragment {
            return when (homeModel.tabs[position]) {
                DisplayMode.SHOW_SONGS -> SongListFragment()
                DisplayMode.SHOW_ALBUMS -> AlbumListFragment()
                DisplayMode.SHOW_ARTISTS -> ArtistListFragment()
                DisplayMode.SHOW_GENRES -> GenreListFragment()
            }
        }
    }
}
