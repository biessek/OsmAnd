package net.osmand.plus.base;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.IdRes;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.text.ClipboardManager;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.OverScroller;
import android.widget.Toast;

import net.osmand.AndroidUtils;
import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.LockableScrollView;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.mapcontextmenu.InterceptorLinearLayout;
import net.osmand.plus.views.controls.HorizontalSwipeConfirm;

import static net.osmand.plus.mapcontextmenu.MapContextMenuFragment.CURRENT_Y_UNDEFINED;
import static net.osmand.plus.mapcontextmenu.MenuBuilder.SHADOW_HEIGHT_TOP_DP;

public abstract class ContextMenuFragment extends BaseOsmAndFragment {

	public static class MenuState {
		public static final int HEADER_ONLY = 1;
		public static final int HALF_SCREEN = 2;
		public static final int FULL_SCREEN = 4;
	}

	public static final int ANIMATION_DURATION = 200;
	public static final String MENU_STATE_KEY = "menu_state_key";

	private InterceptorLinearLayout mainView;
	private View view;
	private OnLayoutChangeListener containerLayoutListener;

	private boolean portrait;
	private boolean nightMode;
	private boolean moving;
	private boolean forceUpdateLayout;
	private boolean initLayout = true;
	private boolean wasDrawerDisabled;
	private boolean paused;

	private int minHalfY;
	private int topScreenPosY;
	private int menuFullHeightMax;
	private int menuBottomViewHeight;
	private int menuFullHeight;
	private int screenHeight;
	private int viewHeight;
	private int topShadowMargin;
	private int currentMenuState;
	private int shadowHeight;
	private int toolbarHeight;
	private int statusBarHeight;

	private String preferredMapLang;
	private boolean transliterateNames;

	private ContextMenuFragmentListener listener;

	public interface ContextMenuFragmentListener {
		void onContextMenuYPosChanged(@NonNull ContextMenuFragment fragment, int y, boolean animated);
		void onContextMenuStateChanged(@NonNull ContextMenuFragment fragment, int menuState);
		void onContextMenuDismiss(@NonNull ContextMenuFragment fragment);
	}

	@LayoutRes
	public abstract int getMainLayoutId();

	@IdRes
	public abstract int getMainViewId();

	@IdRes
	public abstract int getBottomScrollViewId();

	public abstract int getHeaderViewHeight();

	public boolean isSingleFragment() {
		return true;
	}

	public String getFragmentTag() {
		return this.getClass().getName();
	}

	@Nullable
	public InterceptorLinearLayout getMainView() {
		return mainView;
	}

	public boolean isNightMode() {
		return nightMode;
	}

	public String getPreferredMapLang() {
		return preferredMapLang;
	}

	public boolean isTransliterateNames() {
		return transliterateNames;
	}

	public boolean isPaused() {
		return paused;
	}

	@Nullable
	public MapActivity getMapActivity() {
		FragmentActivity activity = getActivity();
		if (activity instanceof MapActivity) {
			return (MapActivity) activity;
		} else {
			return null;
		}
	}

	@NonNull
	public MapActivity requireMapActivity() {
		FragmentActivity activity = getActivity();
		if (!(activity instanceof MapActivity)) {
			throw new IllegalStateException("Fragment " + this + " not attached to an activity.");
		}
		return (MapActivity) activity;
	}

	public ContextMenuFragmentListener getListener() {
		return listener;
	}

	public void setListener(ContextMenuFragmentListener listener) {
		this.listener = listener;
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		MapActivity mapActivity = requireMapActivity();
		OsmandApplication app = mapActivity.getMyApplication();

		preferredMapLang = app.getSettings().MAP_PREFERRED_LOCALE.get();
		transliterateNames = app.getSettings().MAP_TRANSLITERATE_NAMES.get();

		view = inflater.inflate(getMainLayoutId(), container, false);
		currentMenuState = getInitialMenuState();
		Bundle args = getArguments();
		if (args != null) {
			currentMenuState = args.getInt(MENU_STATE_KEY);
		}

		if (isSingleFragment()) {
			AndroidUtils.addStatusBarPadding21v(getActivity(), view);
		}

		portrait = AndroidUiHelper.isOrientationPortrait(mapActivity);
		topShadowMargin = AndroidUtils.dpToPx(mapActivity, 9f);
		statusBarHeight = AndroidUtils.getStatusBarHeight(mapActivity);

		shadowHeight = AndroidUtils.dpToPx(mapActivity, SHADOW_HEIGHT_TOP_DP);
		topScreenPosY = addStatusBarHeightIfNeeded(-shadowHeight);

		toolbarHeight = app.getResources().getDimensionPixelSize(R.dimen.dashboard_map_toolbar);

		mainView = view.findViewById(getMainViewId());
		nightMode = app.getDaynightHelper().isNightModeForMapControls();

		processScreenHeight(container);

		minHalfY = viewHeight - (int) (viewHeight * .75f);

		LockableScrollView bottomScrollView = (LockableScrollView) view.findViewById(getBottomScrollViewId());
		bottomScrollView.setScrollingEnabled(false);
		AndroidUtils.setBackground(app, bottomScrollView, nightMode, R.color.route_info_bg_light, R.color.route_info_bg_dark);

		AndroidUtils.setBackground(app, mainView, nightMode, R.drawable.bg_map_context_menu_light, R.drawable.bg_map_context_menu_dark);

		if (!portrait) {
			final TypedValue typedValueAttr = new TypedValue();
			mapActivity.getTheme().resolveAttribute(R.attr.left_menu_view_bg, typedValueAttr, true);
			mainView.setBackgroundResource(typedValueAttr.resourceId);
			mainView.setLayoutParams(new FrameLayout.LayoutParams(getResources().getDimensionPixelSize(R.dimen.dashboard_land_width), ViewGroup.LayoutParams.MATCH_PARENT));

			FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(AndroidUtils.dpToPx(mapActivity, 345f), ViewGroup.LayoutParams.WRAP_CONTENT);
			params.gravity = Gravity.BOTTOM;
		}

		final GestureDetector swipeDetector = new GestureDetector(app, new HorizontalSwipeConfirm(true));

		final OnTouchListener slideTouchListener = new OnTouchListener() {
			private float dy;
			private float dyMain;
			private float mDownY;

			private int minimumVelocity;
			private int maximumVelocity;
			private VelocityTracker velocityTracker;
			private OverScroller scroller;

			private boolean slidingUp;
			private boolean slidingDown;

			{
				OsmandApplication app = requireMyApplication();
				scroller = new OverScroller(app);
				final ViewConfiguration configuration = ViewConfiguration.get(app);
				minimumVelocity = configuration.getScaledMinimumFlingVelocity();
				maximumVelocity = configuration.getScaledMaximumFlingVelocity();
			}

			@Override
			public boolean onTouch(View v, MotionEvent event) {

				if (!portrait) {
					if (swipeDetector.onTouchEvent(event)) {
						dismiss();

						recycleVelocityTracker();
						return true;
					}
				}

				switch (event.getAction()) {
					case MotionEvent.ACTION_DOWN:
						mDownY = event.getRawY();
						dy = event.getY();
						dyMain = getViewY();

						initOrResetVelocityTracker();
						velocityTracker.addMovement(event);
						break;

					case MotionEvent.ACTION_MOVE:
						if (Math.abs(event.getRawY() - mDownY) > mainView.getTouchSlop()) {
							moving = true;
						}
						if (moving) {
							float y = event.getY();
							float newY = getViewY() + (y - dy);
							if (!portrait && newY > topScreenPosY) {
								newY = topScreenPosY;
							}
							setViewY((int) newY, false, false);

							ViewGroup.LayoutParams lp = mainView.getLayoutParams();
							lp.height = view.getHeight() - (int) newY + 10;
							mainView.setLayoutParams(lp);
							mainView.requestLayout();

							float newEventY = newY - (dyMain - dy);
							MotionEvent ev = MotionEvent.obtain(event.getDownTime(), event.getEventTime(), event.getAction(),
									event.getX(), newEventY, event.getMetaState());

							initVelocityTrackerIfNotExists();
							velocityTracker.addMovement(ev);
						}

						break;

					case MotionEvent.ACTION_UP:
						if (moving) {
							moving = false;
							int currentY = getViewY();

							final VelocityTracker velocityTracker = this.velocityTracker;
							velocityTracker.computeCurrentVelocity(1000, maximumVelocity);
							int initialVelocity = (int) velocityTracker.getYVelocity();

							if ((Math.abs(initialVelocity) > minimumVelocity)) {

								scroller.abortAnimation();
								scroller.fling(0, currentY, 0, initialVelocity, 0, 0,
										Math.min(viewHeight - menuFullHeightMax, getFullScreenTopPosY()),
										screenHeight,
										0, 0);
								currentY = scroller.getFinalY();
								scroller.abortAnimation();

								slidingUp = initialVelocity < -2000;
								slidingDown = initialVelocity > 2000;
							} else {
								slidingUp = false;
								slidingDown = false;
							}

							changeMenuState(currentY, slidingUp, slidingDown, true);
						}
						recycleVelocityTracker();
						break;
					case MotionEvent.ACTION_CANCEL:
						moving = false;
						recycleVelocityTracker();
						break;

				}
				return true;
			}

			private void initOrResetVelocityTracker() {
				if (velocityTracker == null) {
					velocityTracker = VelocityTracker.obtain();
				} else {
					velocityTracker.clear();
				}
			}

			private void initVelocityTrackerIfNotExists() {
				if (velocityTracker == null) {
					velocityTracker = VelocityTracker.obtain();
					velocityTracker.clear();
				}
			}

			private void recycleVelocityTracker() {
				if (velocityTracker != null) {
					velocityTracker.recycle();
					velocityTracker = null;
				}
			}
		};

		((InterceptorLinearLayout) mainView).setListener(slideTouchListener);
		mainView.setOnTouchListener(slideTouchListener);

		containerLayoutListener = new OnLayoutChangeListener() {
			@Override
			public void onLayoutChange(View view, int left, int top, int right, int bottom,
									   int oldLeft, int oldTop, int oldRight, int oldBottom) {
				if (forceUpdateLayout || bottom != oldBottom) {
					forceUpdateLayout = false;
					processScreenHeight(view.getParent());
					runLayoutListener();
				}
			}
		};

		return view;
	}

	private int addStatusBarHeightIfNeeded(int res) {
		MapActivity mapActivity = getMapActivity();
		if (Build.VERSION.SDK_INT >= 21 && mapActivity != null) {
			return res + (isSingleFragment() ? statusBarHeight : -statusBarHeight);
		}
		return res;
	}

	public int getTopShadowMargin() {
		int res = topShadowMargin;
		if (Build.VERSION.SDK_INT >= 21 && !isSingleFragment()) {
			res -= statusBarHeight;
		}
		return res;
	}

	public int getInitialMenuState() {
		return MenuState.FULL_SCREEN;
	}

	public boolean slideUp() {
		int v = currentMenuState;
		for (int i = 0; i < 2; i++) {
			v = v << 1;
			if ((v & getSupportedMenuStates()) != 0) {
				currentMenuState = v;
				return true;
			}
		}
		return false;
	}

	public boolean slideDown() {
		int v = currentMenuState;
		for (int i = 0; i < 2; i++) {
			v = v >> 1;
			if ((v & getSupportedMenuStates()) != 0) {
				currentMenuState = v;
				return true;
			}
		}
		return false;
	}

	public int getCurrentMenuState() {
		return currentMenuState;
	}

	public int getSupportedMenuStates() {
		if (!portrait) {
			return MenuState.FULL_SCREEN;
		} else {
			return getSupportedMenuStatesPortrait();
		}
	}

	public int getSupportedMenuStatesPortrait() {
		return MenuState.HEADER_ONLY | MenuState.HALF_SCREEN | MenuState.FULL_SCREEN;
	}

	@Override
	public void onResume() {
		super.onResume();
		paused = false;
		ViewParent parent = view.getParent();
		if (parent != null && containerLayoutListener != null) {
			((View) parent).addOnLayoutChangeListener(containerLayoutListener);
		}
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			mapActivity.getMapLayers().getMapControlsLayer().showMapControlsIfHidden();
			wasDrawerDisabled = mapActivity.isDrawerDisabled();
			if (!wasDrawerDisabled) {
				mapActivity.disableDrawer();
			}
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		paused = true;
		if (view != null) {
			ViewParent parent = view.getParent();
			if (parent != null && containerLayoutListener != null) {
				((View) parent).removeOnLayoutChangeListener(containerLayoutListener);
			}
		}
		MapActivity mapActivity = getMapActivity();
		if (!wasDrawerDisabled && mapActivity != null) {
			mapActivity.enableDrawer();
		}
	}

	public int getViewY() {
		return (int) mainView.getY();
	}

	private void setViewY(int y, boolean animated, boolean adjustMapPos) {
		mainView.setY(y);
		ContextMenuFragmentListener listener = this.listener;
		if (listener != null) {
			listener.onContextMenuYPosChanged(this, y, false);
		}
	}

	private void processScreenHeight(ViewParent parent) {
		View container = (View) parent;
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			screenHeight = container.getHeight() + statusBarHeight;
			viewHeight = screenHeight - statusBarHeight;
			minHalfY = viewHeight - (int) (viewHeight * .75f);
		}
	}

	private int getFullScreenTopPosY() {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			int res = topShadowMargin + toolbarHeight;
			if (Build.VERSION.SDK_INT >= 21 && !isSingleFragment()) {
				res -= statusBarHeight;
			}
			return res;
		} else {
			return 0;
		}
	}

	private int getHeaderOnlyTopY() {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			if (getHeaderViewHeight() > 0) {
				return viewHeight - getHeaderViewHeight() - shadowHeight;
			} else {
				return viewHeight - AndroidUtils.dpToPx(mapActivity, 48f) - shadowHeight;
			}
		} else {
			return 0;
		}
	}

	private int getMenuStatePosY(int menuState) {
		if (!portrait) {
			return topScreenPosY;
		}
		switch (menuState) {
			case MenuState.HEADER_ONLY:
				return getHeaderOnlyTopY();
			case MenuState.HALF_SCREEN:
				return minHalfY;
			case MenuState.FULL_SCREEN:
				return getFullScreenTopPosY();
			default:
				return 0;
		}
	}

	public void openMenuFullScreen() {
		changeMenuState(getMenuStatePosY(MenuState.FULL_SCREEN), false, false, true);
	}

	public void openMenuHeaderOnly() {
		if (portrait) {
			changeMenuState(getMenuStatePosY(MenuState.HEADER_ONLY), false, false, true);
		}
	}

	public void openMenuHalfScreen() {
		if (portrait) {
			changeMenuState(getMenuStatePosY(MenuState.HALF_SCREEN), false, false, true);
		}
	}

	public void openMenuScreen(int menuState, boolean animated) {
		if (portrait) {
			changeMenuState(getMenuStatePosY(menuState), false, false, animated);
		}
	}

	private void changeMenuState(int currentY, boolean slidingUp, boolean slidingDown, boolean animated) {
		boolean needCloseMenu = false;

		int currentMenuState = getCurrentMenuState();
		if (portrait) {
			int headerDist = Math.abs(currentY - getMenuStatePosY(MenuState.HEADER_ONLY));
			int halfDist = Math.abs(currentY - getMenuStatePosY(MenuState.HALF_SCREEN));
			int fullDist = Math.abs(currentY - getMenuStatePosY(MenuState.FULL_SCREEN));
			int newState;
			if (headerDist < halfDist && headerDist < fullDist) {
				newState = MenuState.HEADER_ONLY;
			} else if (halfDist < headerDist && halfDist < fullDist) {
				newState = MenuState.HALF_SCREEN;
			} else {
				newState = MenuState.FULL_SCREEN;
			}

			if (slidingDown && currentMenuState == MenuState.FULL_SCREEN && getViewY() < getFullScreenTopPosY()) {
				slidingDown = false;
				newState = MenuState.FULL_SCREEN;
			}
			if (menuBottomViewHeight > 0 && slidingUp) {
				while (getCurrentMenuState() != newState) {
					if (!slideUp()) {
						break;
					}
				}
			} else if (slidingDown) {
				if (currentMenuState == MenuState.HEADER_ONLY) {
					needCloseMenu = true;
				} else {
					while (getCurrentMenuState() != newState) {
						if (!slideDown()) {
							needCloseMenu = true;
							break;
						}
					}
				}
			} else {
				if (currentMenuState < newState) {
					while (getCurrentMenuState() != newState) {
						if (!slideUp()) {
							break;
						}
					}
				} else {
					while (getCurrentMenuState() != newState) {
						if (!slideDown()) {
							break;
						}
					}
				}
			}
		}
		int newMenuState = getCurrentMenuState();
		boolean needMapAdjust = currentMenuState != newMenuState && newMenuState != MenuState.FULL_SCREEN;

		applyPosY(currentY, needCloseMenu, needMapAdjust, currentMenuState, newMenuState, 0, animated);

		ContextMenuFragmentListener listener = this.listener;
		if (listener != null) {
			listener.onContextMenuStateChanged(this, newMenuState);
		}
	}


	private int getPosY(final int currentY, boolean needCloseMenu, int previousState) {
		if (needCloseMenu) {
			return screenHeight;
		}
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null) {
			return 0;
		}

		int destinationState = getCurrentMenuState();
		int posY = 0;
		switch (destinationState) {
			case MenuState.HEADER_ONLY:
				posY = getMenuStatePosY(MenuState.HEADER_ONLY);
				break;
			case MenuState.HALF_SCREEN:
				posY = getMenuStatePosY(MenuState.HALF_SCREEN);
				break;
			case MenuState.FULL_SCREEN:
				if (currentY != CURRENT_Y_UNDEFINED) {
					int maxPosY = viewHeight - menuFullHeightMax;
					int minPosY = getMenuStatePosY(MenuState.FULL_SCREEN);
					if (maxPosY > minPosY) {
						maxPosY = minPosY;
					}
					if (currentY > minPosY || previousState != MenuState.FULL_SCREEN) {
						posY = minPosY;
					} else if (currentY < maxPosY) {
						posY = maxPosY;
					} else {
						posY = currentY;
					}
				} else {
					posY = getMenuStatePosY(MenuState.FULL_SCREEN);
				}
				break;
			default:
				break;
		}

		return posY;
	}

	private void updateMainViewLayout(int posY) {
		MapActivity mapActivity = getMapActivity();
		if (view != null && mapActivity != null) {
			ViewGroup.LayoutParams lp = mainView.getLayoutParams();
			lp.height = view.getHeight() - posY;
			mainView.setLayoutParams(lp);
			mainView.requestLayout();
		}
	}

	private void applyPosY(final int currentY, final boolean needCloseMenu, boolean needMapAdjust,
						   final int previousMenuState, final int newMenuState, int dZoom, boolean animated) {
		final int posY = getPosY(currentY, needCloseMenu, previousMenuState);
		if (getViewY() != posY || dZoom != 0) {
			if (posY < getViewY()) {
				updateMainViewLayout(posY);
			}

			if (animated) {
				mainView.animate().y(posY)
						.setDuration(ANIMATION_DURATION)
						.setInterpolator(new DecelerateInterpolator())
						.setListener(new AnimatorListenerAdapter() {

							boolean canceled = false;

							@Override
							public void onAnimationCancel(Animator animation) {
								canceled = true;
							}

							@Override
							public void onAnimationEnd(Animator animation) {
								if (!canceled) {
									if (needCloseMenu) {
										dismiss();
									} else {
										updateMainViewLayout(posY);
										if (previousMenuState != 0 && newMenuState != 0 && previousMenuState != newMenuState) {
											doAfterMenuStateChange(previousMenuState, newMenuState);
										}
									}
								}
							}
						}).start();
			} else {
				if (needCloseMenu) {
					dismiss();
				} else {
					mainView.setY(posY);
					updateMainViewLayout(posY);
					if (previousMenuState != 0 && newMenuState != 0 && previousMenuState != newMenuState) {
						doAfterMenuStateChange(previousMenuState, newMenuState);
					}
				}
			}

			ContextMenuFragmentListener listener = this.listener;
			if (listener != null) {
				listener.onContextMenuYPosChanged(this, posY, true);
			}
		}
	}

	public void animateView(@NonNull View view, int y) {
		view.animate().y(y)
				.setDuration(ANIMATION_DURATION)
				.setInterpolator(new DecelerateInterpolator())
				.start();
	}

	protected void doAfterMenuStateChange(int previousState, int newState) {
		runLayoutListener();
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	protected void runLayoutListener() {
		if (view != null) {
			ViewTreeObserver vto = view.getViewTreeObserver();
			vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
				@Override
				public void onGlobalLayout() {
					if (view != null) {
						ViewTreeObserver obs = view.getViewTreeObserver();
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
							obs.removeOnGlobalLayoutListener(this);
						} else {
							obs.removeGlobalOnLayoutListener(this);
						}

						if (getActivity() == null) {
							return;
						}
						menuFullHeight = mainView.getHeight();
						menuBottomViewHeight = menuFullHeight;

						menuFullHeightMax = view.findViewById(R.id.route_menu_cards_container).getHeight();

						if (!moving) {
							doLayoutMenu();
						}
						initLayout = false;

						ContextMenuFragmentListener listener = ContextMenuFragment.this.listener;
						if (listener != null) {
							listener.onContextMenuStateChanged(ContextMenuFragment.this, getCurrentMenuState());
						}
					}
				}
			});
		}
	}

	private void doLayoutMenu() {
		final int posY = getPosY(initLayout ? CURRENT_Y_UNDEFINED : getViewY(), false, getCurrentMenuState());
		setViewY(posY, true, !initLayout);
		updateMainViewLayout(posY);
	}

	public void dismiss() {
		if (isSingleFragment()) {
			FragmentActivity activity = getActivity();
			if (activity != null) {
				try {
					activity.getSupportFragmentManager().popBackStack(getFragmentTag(),
							FragmentManager.POP_BACK_STACK_INCLUSIVE);
				} catch (Exception e) {
					//
				}
			}
		}
		ContextMenuFragmentListener listener = this.listener;
		if (listener != null) {
			listener.onContextMenuDismiss(this);
		}
	}

	public void showLocationOnMap(LatLon latLon) {
		OsmandApplication app = getMyApplication();
		if (latLon == null && app != null) {
			Location lastLocation = app.getLocationProvider().getLastKnownLocation();
			if (lastLocation != null) {
				latLon = new LatLon(lastLocation.getLatitude(), lastLocation.getLongitude());
			}
		}
		if (latLon != null) {
			openMenuHeaderOnly();
			showOnMap(latLon);
		}
	}

	public void showOnMap(@NonNull LatLon latLon) {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			int currentZoom = mapActivity.getMapView().getZoom();
			mapActivity.getMapView().getAnimatedDraggingThread().startMoving(latLon.getLatitude(), latLon.getLongitude(), Math.max(15, currentZoom), true);
		}
	}

	public void showOnMap(@NonNull LatLon start, @NonNull LatLon end) {
		double left = Math.min(start.getLongitude(), end.getLongitude());
		double right = Math.max(start.getLongitude(), end.getLongitude());
		double top = Math.max(start.getLatitude(), end.getLatitude());
		double bottom = Math.min(start.getLatitude(), end.getLatitude());
		QuadRect rect = new QuadRect(left, top, right, bottom);
		openMenuHeaderOnly();
		fitRectOnMap(rect);
	}

	public void fitRectOnMap(QuadRect rect) {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			RotatedTileBox tb = mapActivity.getMapView().getCurrentRotatedTileBox().copy();
			int tileBoxWidthPx = 0;
			int tileBoxHeightPx = 0;

			boolean portrait = AndroidUiHelper.isOrientationPortrait(mapActivity);
			if (!portrait) {
				tileBoxWidthPx = tb.getPixWidth() - view.getWidth();
			} else {
				tileBoxHeightPx = getHeaderOnlyTopY();
			}
			if (tileBoxHeightPx > 0) {
				int topMarginPx = toolbarHeight;
				mapActivity.getMapView().fitRectToMap(rect.left, rect.right, rect.top, rect.bottom,
						tileBoxWidthPx, tileBoxHeightPx, topMarginPx);
			}
		}
	}

	public int dpToPx(float dp) {
		return AndroidUtils.dpToPx(requireMyApplication(), dp);
	}

	protected void copyToClipboard(@NonNull String text, @NonNull Context ctx) {
		Object systemService = ctx.getSystemService(Activity.CLIPBOARD_SERVICE);
		if (systemService instanceof ClipboardManager) {
			((ClipboardManager) systemService).setText(text);
			Toast.makeText(ctx,
					ctx.getResources().getString(R.string.copied_to_clipboard) + ":\n" + text,
					Toast.LENGTH_SHORT).show();
		}
	}

	protected static boolean showInstance(@NonNull MapActivity mapActivity, ContextMenuFragment fragment) {
		try {
			mapActivity.getSupportFragmentManager()
					.beginTransaction()
					.replace(R.id.routeMenuContainer, fragment, fragment.getFragmentTag())
					.addToBackStack(fragment.getFragmentTag())
					.commitAllowingStateLoss();

			return true;

		} catch (RuntimeException e) {
			return false;
		}
	}
}