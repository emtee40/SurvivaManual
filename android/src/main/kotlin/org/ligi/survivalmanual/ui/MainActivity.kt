package org.ligi.survivalmanual.ui

import android.annotation.TargetApi
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.support.v4.view.MenuItemCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.SearchView
import android.support.v7.widget.Toolbar
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.bookmark.view.*
import org.ligi.compat.WebViewCompat
import org.ligi.kaxt.*
import org.ligi.snackengage.SnackEngage
import org.ligi.snackengage.snacks.DefaultRateSnack
import org.ligi.snackengage.snacks.RateSnack
import org.ligi.survivalmanual.EventTracker
import org.ligi.survivalmanual.R
import org.ligi.survivalmanual.adapter.EditingRecyclerAdapter
import org.ligi.survivalmanual.adapter.MarkdownRecyclerAdapter
import org.ligi.survivalmanual.adapter.SearchResultRecyclerAdapter
import org.ligi.survivalmanual.functions.CaseInsensitiveSearch
import org.ligi.survivalmanual.functions.convertMarkdownToHtml
import org.ligi.survivalmanual.functions.isImage
import org.ligi.survivalmanual.functions.splitText
import org.ligi.survivalmanual.model.*
import org.ligi.tracedroid.logging.Log

class MainActivity : BaseActivity() {

    private val drawerToggle by lazy { ActionBarDrawerToggle(this, drawer_layout, org.ligi.survivalmanual.R.string.drawer_open, org.ligi.survivalmanual.R.string.drawer_close) }

    val survivalContent by lazy { SurvivalContent(assets) }

    lateinit var currentUrl: String
    lateinit var currentTopicName: String

    lateinit var textInput: MutableList<String>

    fun imageWidth(): Int {
        val totalWidthPadding = (resources.getDimension(org.ligi.survivalmanual.R.dimen.content_padding) * 2).toInt()
        return Math.min(contentRecycler.width - totalWidthPadding, contentRecycler.height)
    }

    val onURLClick: (String) -> Unit = {
        EventTracker.trackContent(it)
        if (it.startsWith("http")) {
            startActivityFromURL(it)
        } else if (!processProductLinks(it, this)) {

            if (isImage(it)) {
                val intent = Intent(this, ImageViewActivity::class.java)
                intent.putExtra("URL", it)
                startActivity(intent)
            } else {
                processURL(it)
            }
        }
    }


    private val linearLayoutManager by lazy { LinearLayoutManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(org.ligi.survivalmanual.R.layout.activity_main)

        drawer_layout.addDrawerListener(drawerToggle)
        setSupportActionBar(findViewById<Toolbar>(org.ligi.survivalmanual.R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        navigationView.setNavigationItemSelectedListener { item ->
            drawer_layout.closeDrawers()
            processURL(NavigationEntryMap[item.itemId].entry.url)
            true
        }

        contentRecycler.layoutManager = linearLayoutManager

        class RememberPositionOnScroll : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
                State.lastScrollPos = (contentRecycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                super.onScrolled(recyclerView, dx, dy)
            }
        }

        contentRecycler.addOnScrollListener(RememberPositionOnScroll())

        SnackEngage.from(this).withSnack(DefaultRateSnack()).build().engageWhenAppropriate()

        contentRecycler.post {
            if (intent.data == null || !processURL(intent.data.path.replace("/", ""))) {
                if (!processURL(State.lastVisitedURL)) {
                    processURL(State.FALLBACK_URL)
                }
            }

            switchMode(false)
        }

        if (State.isInitialOpening) {
            drawer_layout.openDrawer(Gravity.LEFT)
            State.isInitialOpening = false
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(org.ligi.survivalmanual.R.id.action_search).isVisible = State.allowSearch()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(org.ligi.survivalmanual.R.menu.main, menu)
        if (Build.VERSION.SDK_INT >= 19) {
            menuInflater.inflate(org.ligi.survivalmanual.R.menu.print, menu)
        }

        val searchView = MenuItemCompat.getActionView(menu.findItem(org.ligi.survivalmanual.R.id.action_search)) as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(searchTerm: String): Boolean {
                State.searchTerm = searchTerm
                val adapter = contentRecycler.adapter
                if (adapter is MarkdownRecyclerAdapter) {

                    val positionForWord = adapter.getPositionForWord(searchTerm)

                    if (positionForWord != null) {
                        contentRecycler.smoothScrollToPosition(positionForWord)
                    } else {
                        contentRecycler.adapter = SearchResultRecyclerAdapter(searchTerm, survivalContent, {
                            processURL(it)
                            closeKeyboard()
                        }).apply { toast() }

                    }

                    adapter.notifyDataSetChanged()
                } else if (adapter is SearchResultRecyclerAdapter) {
                    adapter.changeTerm(searchTerm)
                    adapter.toast()
                    if (survivalContent.getMarkdown(currentUrl)!!.contains(searchTerm)) {
                        contentRecycler.adapter = MarkdownRecyclerAdapter(textInput, imageWidth(), onURLClick)
                        State.searchTerm = searchTerm
                    }
                }

                return true
            }

            override fun onQueryTextSubmit(query: String?) = true
        })
        return super.onCreateOptionsMenu(menu)
    }

    fun SearchResultRecyclerAdapter.toast() = {
        if (list.isEmpty()) {
            Toast.makeText(this@MainActivity, State.searchTerm + " not found", Toast.LENGTH_LONG).show()
        }
    }

    private fun MarkdownRecyclerAdapter.getPositionForWord(searchTerm: String): Int? {
        val first = Math.max(linearLayoutManager.findFirstVisibleItemPosition(), 0)
        val search = CaseInsensitiveSearch(searchTerm)

        val next = (first..list.lastIndex).firstOrNull {
            search.isInContent(list[it])
        }
        return next
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {

        org.ligi.survivalmanual.R.id.menu_settings -> {
            startActivityFromClass(PreferenceActivity::class.java)
            true
        }

        org.ligi.survivalmanual.R.id.menu_share -> {
            EventTracker.trackGeneric("share")
            val intent = Intent(Intent.ACTION_SEND)
            intent.putExtra(Intent.EXTRA_TEXT, RateSnack().getUri(this).toString())
            intent.type = "text/plain"
            startActivity(Intent.createChooser(intent, null))
            true
        }

        org.ligi.survivalmanual.R.id.menu_rate -> {
            EventTracker.trackGeneric("rate")
            startActivityFromURL(RateSnack().getUri(this))
            true
        }

        org.ligi.survivalmanual.R.id.menu_print -> {
            EventTracker.trackGeneric("print", currentUrl)
            val newWebView = WebView(this@MainActivity)
            newWebView.setWebViewClient(object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                override fun onPageFinished(view: WebView, url: String) = createWebPrintJob(view)
            })

            val htmlDocument = convertMarkdownToHtml(survivalContent.getMarkdown(currentUrl)!!)

            newWebView.loadDataWithBaseURL("file:///android_asset/md/", htmlDocument, "text/HTML", "UTF-8", null)

            true
        }

        org.ligi.survivalmanual.R.id.menu_bookmark -> {
            val view = inflate(R.layout.bookmark)
            view.topicText.text = currentTopicName
            AlertDialog.Builder(this)
                    .setView(view)
                    .setTitle("Add Bookmark")
                    .setPositiveButton("Bookmark", { _: DialogInterface, _: Int ->
                        Bookmarks.persist(Bookmark(currentUrl, view.commentEdit.text.toString(), ""))
                    })
                    .setNegativeButton("Cancel", { _: DialogInterface, _: Int -> })
                    .show()
            true
        }

        else -> drawerToggle.onOptionsItemSelected(item)
    }


    @TargetApi(19)
    private fun createWebPrintJob(webView: WebView) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = getString(org.ligi.survivalmanual.R.string.app_name) + " Document"
        val printAdapter = WebViewCompat.createPrintDocumentAdapter(webView, jobName)
        printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
    }

    private fun processURL(url: String): Boolean {

        appbar.setExpanded(true)
        Log.i("processing url $url")

        VisitedURLStore.add(url)
        val titleResByURL = getTitleResByURL(url) ?: return false

        currentUrl = url

        currentTopicName = getString(titleResByURL)
        EventTracker.trackContent(url)

        supportActionBar?.subtitle = currentTopicName

        State.lastVisitedURL = url

        survivalContent.getMarkdown(currentUrl)?.let { markdown ->
            textInput = splitText(markdown)

            val newAdapter = MarkdownRecyclerAdapter(textInput, imageWidth(), onURLClick)
            contentRecycler.adapter = newAdapter
            if (!State.searchTerm.isNullOrBlank()) {
                newAdapter.notifyDataSetChanged()
                newAdapter.getPositionForWord(State.searchTerm!!)?.let {
                    contentRecycler.scrollToPosition(it)
                }

            }
            navigationView.refresh()

            return true
        }

        return false
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    var lastFontSize = State.getFontSize()
    var lastNightMode = State.nightModeString()
    var lastAllowSelect = State.allowSelect()

    override fun onResume() {
        super.onResume()
        navigationView.refresh()
        fab.setVisibility(State.allowEdit())
        if (lastFontSize != State.getFontSize()) {
            contentRecycler.adapter?.notifyDataSetChanged()
            lastFontSize = State.getFontSize()
        }
        if (lastAllowSelect != State.allowSelect()) {
            recreateWhenPossible()
            lastAllowSelect = State.allowSelect()
        }
        if (lastNightMode != State.nightModeString()) {
            recreateWhenPossible()
            lastNightMode = State.nightModeString()
        }

        supportInvalidateOptionsMenu()
    }

    fun switchMode(editing: Boolean) {
        fab.setOnClickListener {
            switchMode(!editing)
        }

        if (editing) {
            fab.setImageResource(org.ligi.survivalmanual.R.drawable.ic_image_remove_red_eye)
            contentRecycler.adapter = EditingRecyclerAdapter(textInput)
        } else {
            fab.setImageResource(org.ligi.survivalmanual.R.drawable.ic_editor_mode_edit)
            contentRecycler.adapter = MarkdownRecyclerAdapter(textInput, imageWidth(), onURLClick)
        }

        contentRecycler.scrollToPosition(State.lastScrollPos)

    }
}
